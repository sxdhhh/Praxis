/*
 * PTY（伪终端）JNI。
 *
 * 来源：Operit `terminal/src/main/jni/pty.c`（207 行），改包名与 JNI 符号前缀。
 *
 * ## 为什么必须要真 PTY，而不是 ProcessBuilder 的管道
 *
 * 管道（pipe）与 PTY 对子进程来说**是两样东西**：
 *
 * | | 管道 | PTY |
 * |---|---|---|
 * | `isatty(0)` | false | **true** |
 * | 行缓冲 / 全缓冲 | 全缓冲（按 4KB 攒） | 行缓冲（立刻出） |
 * | bash 是否进入交互模式 | 否（没有提示符，不做行编辑） | 是 |
 * | Ctrl+C 是否能中断 | **不能**（没有前台进程组的概念） | 能（内核发 SIGINT） |
 * | `vim` / `top` 的全屏绘制 | 会失败（`ioctl TIOCGWINSZ` 拿不到尺寸） | 正常 |
 *
 * 这就是「用管道跑 bash 时 `apt` 的进度条不动、`vim` 一进去就报错」的原因。
 * 想 1:1 对齐 Operit 的终端，PTY 是绕不过去的那一层。
 *
 * ## 四个导出函数各自解决什么
 *
 * - `createSubprocess`：`forkpty` 起子进程，返回 `{pid, masterFd}`。
 * - `waitFor`：等子进程退出并拿退出码。
 * - `getTerminalFlags`：读 termios 的 ICANON/ECHO/ISIG/IEXTEN。
 *   用来判断「子进程是否已经自己改成了 raw 模式」（vim/top 会这么做），
 *   从而知道它是不是在等按键。
 * - `getAvailableBytes`：`FIONREAD` 查有多少字节可读。
 *   用于「有没有输出在等着」而不阻塞。
 * - `setPtyWindowSize`：`TIOCSWINSZ` 设窗口大小。
 *   不设的话 `top`/`vim` 会按 80x24 画，在手机上错位。
 */
#include <jni.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <stdio.h>
#include <android/log.h>
#include <termios.h>

#ifdef __linux__
#include <pty.h>
#elif __APPLE__
#include <util.h>
#endif

#define TAG "PraxisPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/*
 * JNI 符号必须手写（不能靠 RegisterNatives），因为 Kotlin 的
 * `companion object` 编译后类名是 `Pty$Companion`，而 `$` 在 C 符号里
 * 要写成 `_00024`。这也是 Kotlin 侧 `external fun` 必须声明在
 * `companion object` 里、且类名与方法名一处不能改的原因。
 */
JNIEXPORT jintArray JNICALL
Java_dev_praxis_core_terminal_Pty_00024Companion_createSubprocess(
        JNIEnv *env, jobject thiz,
        jobjectArray cmdarray,
        jobjectArray envarray,
        jstring workingDir,
        jint rows,
        jint cols) {

    const char *cwd_jni = (*env)->GetStringUTFChars(env, workingDir, 0);
    char *cwd = strdup(cwd_jni);
    (*env)->ReleaseStringUTFChars(env, workingDir, cwd_jni);

    int cmd_len = (*env)->GetArrayLength(env, cmdarray);
    char **argv = (char **) malloc(sizeof(char *) * (cmd_len + 1));
    for (int i = 0; i < cmd_len; i++) {
        jstring j_cmd_str = (jstring) (*env)->GetObjectArrayElement(env, cmdarray, i);
        const char *cmd_str = (*env)->GetStringUTFChars(env, j_cmd_str, 0);
        argv[i] = strdup(cmd_str);
        (*env)->ReleaseStringUTFChars(env, j_cmd_str, cmd_str);
        (*env)->DeleteLocalRef(env, j_cmd_str);
    }
    argv[cmd_len] = NULL;

    int env_len = (*env)->GetArrayLength(env, envarray);
    char **envp = (char **) malloc(sizeof(char *) * (env_len + 1));
    for (int i = 0; i < env_len; i++) {
        jstring j_env_str = (jstring) (*env)->GetObjectArrayElement(env, envarray, i);
        const char *env_str = (*env)->GetStringUTFChars(env, j_env_str, 0);
        envp[i] = strdup(env_str);
        (*env)->ReleaseStringUTFChars(env, j_env_str, env_str);
        (*env)->DeleteLocalRef(env, j_env_str);
    }
    envp[env_len] = NULL;

    struct termios tt;
    memset(&tt, 0, sizeof(tt));
    tt.c_iflag = ICRNL | IXON | IXANY;
    tt.c_oflag = OPOST | ONLCR;
    tt.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHONL | IEXTEN;
    tt.c_cflag = CS8 | CREAD;
    tt.c_cc[VINTR]    = 'C' - '@';
    tt.c_cc[VQUIT]    = '\\' - '@';
    tt.c_cc[VERASE]   = 0x7f; /* DEL */
    tt.c_cc[VKILL]    = 'U' - '@';
    tt.c_cc[VEOF]     = 'D' - '@';
    tt.c_cc[VSTOP]    = 'S' - '@';
    tt.c_cc[VSUSP]    = 'Z' - '@';
    tt.c_cc[VSTART]   = 'Q' - '@';
    tt.c_cc[VMIN]     = 1;
    tt.c_cc[VTIME]    = 0;

    /*
     * 窗口大小由调用方传（原实现写死 60x40）。
     * 手机上必须按实际屏幕列数算，否则 `top` 的列会错位。
     */
    struct winsize ws;
    ws.ws_row = rows > 0 ? rows : 60;
    ws.ws_col = cols > 0 ? cols : 40;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;

    int master_fd;
    pid_t pid = forkpty(&master_fd, NULL, &tt, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s (errno=%d) argv[0]=%s cwd=%s",
             strerror(errno), errno, cmd_len > 0 ? argv[0] : "(empty)", cwd);
        free(cwd);
        for (int i = 0; i < cmd_len; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < env_len; i++) free(envp[i]);
        free(envp);
        return NULL;
    }

    if (pid == 0) {
        /*
         * 子进程。
         *
         * ## ⚠️ 这里每一步都必须 LOGE（2026-11-03 真机实证后补）
         *
         * 原来只有 `fprintf(stderr, ...)`。而子进程的 stderr **已经重定向进 PTY**，
         * 所以失败信息只会出现在终端画面里，**不进 logcat**。
         *
         * 后果是整个终端最关键的失败路径完全不可观测：
         *
         * ```
         * forkpty 成功 -> 子进程 execve 失败 -> _exit(1)
         *   -> Kotlin 侧 200ms 后 isAlive=false -> 静默丢弃这个 PTY
         *   -> 降级到管道 -> 用户看到 can't access tty
         * ```
         *
         * 真机实测：`logcat -s PraxisPty` **零条**，而终端确实降级了。
         * 一个能让整个终端静默降级的路径不该一行日志都没有。
         */
        if (chdir(cwd) != 0) {
            LOGE("child: chdir(%s) failed: %s", cwd, strerror(errno));
            fprintf(stderr, "chdir to %s failed: %s\n", cwd, strerror(errno));
            _exit(127);
        }
        execve(argv[0], argv, envp);
        /*
         * execve 走到这里说明失败了。errno 是唯一线索：
         *   ENOENT      -> 文件不存在（nativeLibraryDir 里没有那个 .so？）
         *   EACCES      -> 不可执行（SELinux / 权限位）
         *   ENOEXEC     -> 不是可执行格式 / 动态链接器缺失
         *   ETXTBSY     -> 文件正被写
         */
        LOGE("child: execve(%s) failed: %s (errno=%d)", argv[0], strerror(errno), errno);
        fprintf(stderr, "execve(%s) failed: %s\n", argv[0], strerror(errno));
        _exit(127);
    }

    /* 父进程 */
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) {
        free(cwd);
        for (int i = 0; i < cmd_len; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < env_len; i++) free(envp[i]);
        free(envp);
        return NULL;
    }
    jint fill[2];
    fill[0] = pid;
    fill[1] = master_fd;
    (*env)->SetIntArrayRegion(env, result, 0, 2, fill);

    free(cwd);
    for (int i = 0; i < cmd_len; i++) free(argv[i]);
    free(argv);
    for (int i = 0; i < env_len; i++) free(envp[i]);
    free(envp);

    LOGD("forkpty ok: pid=%d fd=%d size=%dx%d", pid, master_fd, ws.ws_row, ws.ws_col);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_00024Companion_waitFor(
        JNIEnv *env, jobject thiz, jint pid) {
    int status;
    waitpid(pid, &status, 0);
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return -1;
}

/*
 * 读 termios 的标志位。
 *  bit 0: ICANON  —— 行缓冲（关掉说明进了 raw 模式，vim/top 会这么做）
 *  bit 1: ECHO    —— 回显
 *  bit 2: ISIG    —— 特殊字符产生信号（Ctrl+C 生效的前提）
 *  bit 3: IEXTEN  —— 扩展输入处理
 */
JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_00024Companion_getTerminalFlags(
        JNIEnv *env, jobject thiz, jint fd) {
    struct termios tt;
    if (tcgetattr(fd, &tt) != 0) {
        LOGE("tcgetattr failed for fd %d", fd);
        return -1;
    }
    jint flags = 0;
    if (tt.c_lflag & ICANON) flags |= 0x01;
    if (tt.c_lflag & ECHO)   flags |= 0x02;
    if (tt.c_lflag & ISIG)   flags |= 0x04;
    if (tt.c_lflag & IEXTEN) flags |= 0x08;
    return flags;
}

/* 查有多少字节可读；-1 表示错误。用于「有没有输出在等」而不阻塞。 */
JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_00024Companion_getAvailableBytes(
        JNIEnv *env, jobject thiz, jint fd) {
    int available = 0;
    if (ioctl(fd, FIONREAD, &available) != 0) {
        LOGE("ioctl FIONREAD failed for fd %d", fd);
        return -1;
    }
    return available;
}

/* 设窗口大小。不设的话 top/vim 按 80x24 画，手机上必然错位。 */
JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_setPtyWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    ws.ws_row = rows;
    ws.ws_col = cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    if (ioctl(fd, TIOCSWINSZ, &ws) != 0) {
        LOGE("ioctl TIOCSWINSZ failed for fd %d", fd);
        return -1;
    }
    return 0;
}

/*
 * ═══════════════════════════════════════════════════════════════════
 * 兼容符号：不带 `_00024Companion` 的版本（2026-11-03 真机实证修复）
 * ═══════════════════════════════════════════════════════════════════
 *
 * ## 真机症状
 *
 * 终端一进就 `can't access tty`，logcat 里：
 *
 * ```
 * E PraxisPty: java.lang.UnsatisfiedLinkError: No implementation found for
 *   int[] dev.praxis.core.terminal.Pty.createSubprocess(java.lang.String[], ...)
 *   (tried Java_dev_praxis_core_terminal_Pty_createSubprocess and
 *          Java_dev_praxis_core_terminal_Pty_createSubprocess___3Ljava_lang_String_2...)
 * ```
 *
 * ## 为什么
 *
 * Kotlin 侧把这些写成 companion object 里的 `@JvmStatic external fun`。
 * `@JvmStatic` 会在**外层类 `Pty`** 上再生成一个静态桥接方法，而 JNI 解析时
 * **优先查外层类那个名字**（`Java_..._Pty_createSubprocess`），
 * 但它没有 native implementation —— 于是 UnsatisfiedLinkError。
 *
 * 证据：同一份文件里 `setPtyWindowSize` 当初就是因为没写 Companion 前缀而能工作，
 * 其余四个写了 `_00024Companion` 的全部失效。两套命名混在同一个库里，
 * 说明这个前缀写法本身没经过真机验证。
 *
 * ## 修法
 *
 * 补上不带前缀的别名，让 JVM 无论按哪个名字解析都能找到实现。
 * 保留带 `_00024Companion` 的旧符号，避免破坏其它调用路径。
 */

JNIEXPORT jintArray JNICALL
Java_dev_praxis_core_terminal_Pty_createSubprocess(
        JNIEnv *env, jclass clazz,
        jobjectArray cmdarray, jobjectArray envarray,
        jstring workingDir, jint rows, jint cols) {
    return Java_dev_praxis_core_terminal_Pty_00024Companion_createSubprocess(
            env, NULL, cmdarray, envarray, workingDir, rows, cols);
}

JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_waitForPid(JNIEnv *env, jclass clazz, jint pid) {
    return Java_dev_praxis_core_terminal_Pty_00024Companion_waitFor(env, NULL, pid);
}

JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_getTerminalFlags(JNIEnv *env, jclass clazz, jint fd) {
    return Java_dev_praxis_core_terminal_Pty_00024Companion_getTerminalFlags(env, NULL, fd);
}

JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_getAvailableBytes(JNIEnv *env, jclass clazz, jint fd) {
    return Java_dev_praxis_core_terminal_Pty_00024Companion_getAvailableBytes(env, NULL, fd);
}

/*
 * `setPtyWindowSize` 的对称别名。
 *
 * 它原本只有 `Pty_`（外层类）形式，其余四个只有 `Pty_00024Companion_`（伴生类）形式。
 * 既然两套命名都在用，就把两套都补齐 —— 无论 JVM 按哪个名字解析都能拿到实现。
 * 只补一边等于在赌 `@JvmStatic` 的生成规则永远不变，而这次真机故障正是
 * 因为赌错了生成规则。
 */
JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_00024Companion_setPtyWindowSize(
        JNIEnv *env, jobject thiz, jint fd, jint rows, jint cols) {
    return Java_dev_praxis_core_terminal_Pty_setPtyWindowSize(env, NULL, fd, rows, cols);
}

/*
 * `waitFor` 的名字不一致问题：Kotlin 侧方法叫 `waitForPid`，
 * 而 C 侧伴生版本历史上写成了 `waitFor`（少了 Pid）。
 * JVM 解析用的是 Kotlin 方法名，所以 `waitForPid` 这个名字必须有实现。
 */
JNIEXPORT jint JNICALL
Java_dev_praxis_core_terminal_Pty_00024Companion_waitForPid(
        JNIEnv *env, jobject thiz, jint pid) {
    return Java_dev_praxis_core_terminal_Pty_00024Companion_waitFor(env, thiz, pid);
}
