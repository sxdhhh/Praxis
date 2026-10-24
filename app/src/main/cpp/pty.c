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
        LOGE("forkpty failed: %s", strerror(errno));
        free(cwd);
        for (int i = 0; i < cmd_len; i++) free(argv[i]);
        free(argv);
        for (int i = 0; i < env_len; i++) free(envp[i]);
        free(envp);
        return NULL;
    }

    if (pid == 0) {
        /* 子进程 */
        if (chdir(cwd) != 0) {
            fprintf(stderr, "chdir to %s failed: %s\n", cwd, strerror(errno));
            _exit(1);
        }
        execve(argv[0], argv, envp);
        fprintf(stderr, "execve(%s) failed: %s\n", argv[0], strerror(errno));
        _exit(1);
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
