package dev.praxis.core.plugin

/**
 * 注入到脚本上下文里的引导脚本（照 Operit 的注册面）。
 *
 * ## 它替换掉了什么
 *
 * Operit 的脚本通过 `ToolPkg.*` 注册 UI 与钩子。Praxis 没有原生桥，
 * 所以先注入一段纯 JS：把注册调用**收集**起来，注册阶段结束时由宿主取走。
 * 这样脚本侧的写法与 Operit 完全一致，作者不用改代码。
 *
 * ## 真实 API 面（来自 docs/TOOLPKG_FORMAT_GUIDE.md §3.2.5）
 *
 * main 脚本必须导出 `registerToolPkg()`，在其中调用：
 *
 * | 函数 | 必填 |
 * |---|---|
 * | registerToolboxUiModule | id + screen |
 * | registerUiRoute | id + screen（route 可省，宿主生成） |
 * | registerNavigationEntry | id + surface +（route 或 action） |
 * | registerDesktopWidget | id + route |
 *
 * `registerToolPkg()` **只声明注册项**：文档明确说 `readResource` 与
 * `wasm.call` 在这个阶段调用会立刻抛异常，所以引导脚本里它们也照此实现 ——
 * 静默成功会让作者以为资源读到了。
 *
 * ## 工具不在这里
 *
 * 工具属于**子包**：子包脚本带 METADATA 注释块，注册名是
 * `<subpackage_id>:<tool_name>`。它是静态声明，不走本引导脚本。
 */
object PluginRuntimeBootstrapScript {

    /** 引导脚本正文。在加载任何包代码之前执行。 */
    val JS: String = """
(function () {
  var reg = { tools: [], uiRoutes: [], navEntries: [], errors: [] };

  function localized(value) {
    if (value === undefined || value === null) return undefined;
    if (typeof value === 'string') return { default: value };
    if (typeof value === 'object') return value;
    return undefined;
  }

  function actionOf(def) {
    var raw = def.action;
    if (typeof raw === 'function') {
      return raw.name ? { function: raw.name } : undefined;
    }
    if (typeof raw === 'string') {
      return raw ? { function: raw } : undefined;
    }
    if (raw && typeof raw === 'object') {
      var name = raw['function'] || raw.functionName;
      return name ? { function: name, function_source: raw.function_source } : undefined;
    }
    return undefined;
  }

  var ToolPkg = {
    registerUiRoute: function (def) {
      if (!def || typeof def !== 'object') {
        reg.errors.push('registerUiRoute expects an object');
        return;
      }
      if (typeof def.screen === 'function' && def.id) {
        root.__praxis_screens[def.id] = def.screen;
      }
      reg.uiRoutes.push({
        id: def.id,
        route: def.route || def.routeId,
        runtime: def.runtime,
        title: localized(def.title),
        keep_alive: !!def.keepAlive,
        has_screen: typeof def.screen === 'function'
      });
    },

    registerNavigationEntry: function (def) {
      if (!def || typeof def !== 'object') {
        reg.errors.push('registerNavigationEntry expects an object');
        return;
      }
      var action = actionOf(def);
      reg.navEntries.push({
        id: def.id,
        route: def.route || def.routeId,
        surface: def.surface,
        title: localized(def.title),
        icon: def.icon,
        order: typeof def.order === 'number' ? def.order : 0,
        action_function: action ? action['function'] : undefined,
        action_source: action ? action.function_source : undefined
      });
    },

    registerToolboxUiModule: function (def) {
      if (!def || typeof def !== 'object') {
        reg.errors.push('registerToolboxUiModule expects an object');
        return;
      }
      if (typeof def.screen === 'function' && def.id) {
        root.__praxis_screens[def.id] = def.screen;
      }
      reg.uiRoutes.push({
        id: def.id,
        route: def.route || def.routeId,
        runtime: def.runtime,
        title: localized(def.title),
        keep_alive: false,
        has_screen: typeof def.screen === 'function',
        toolbox: true
      });
    },

    registerDesktopWidget: function (def) {
      if (!def || typeof def !== 'object') {
        reg.errors.push('registerDesktopWidget expects an object');
        return;
      }
      if (typeof def.screen === 'function' && def.id) {
        root.__praxis_screens[def.id] = def.screen;
      }
      reg.uiRoutes.push({
        id: def.id,
        route: def.route || def.routeId,
        runtime: 'widget',
        title: localized(def.title),
        keep_alive: false,
        has_screen: false
      });
    },

    // 文档明确：注册阶段这两个调用会立刻抛异常。照此实现，
    // 免得脚本作者以为资源读到了、WASM 调上了。
    readResource: function () {
      throw new Error('ToolPkg.readResource is not available during registerToolPkg()');
    },
    wasm: {
      call: function () {
        throw new Error('ToolPkg.wasm.call is not available during registerToolPkg()');
      }
    }
  };

  var root = typeof globalThis !== 'undefined' ? globalThis : this;
  root.ToolPkg = ToolPkg;

  // 入口 id -> screen 函数。
  //
  // 为什么留句柄而不只留个布尔：渲染时要**调用**它。路径不可靠（模块名由
  // require 解析决定），句柄是注册那一刻作者给的那个函数，不会错。
  root.__praxis_screens = {};

  root.__praxis_registrations_json = function () {
    return JSON.stringify(reg);
  };

  /**
   * 极简 CommonJS 加载器。
   *
   * ## 为什么必须有
   *
   * 真实包是打包产物：sidebar_sillytavern 的 dist/main.js 会 require
   * 同包的 dist/ui/.../index.ui.js。没有 require 的话，注册还没开始就抛错，
   * 而作者从界面只能看到「没反应」。
   *
   * 同步读文件是必须的 —— CommonJS 的 require 就是同步语义，
   * 所以宿主通过 PraxisHost.readFile 提供同步读取。
   */
  root.__praxis_modules = {};

  /**
   * 把相对请求解析成包根相对路径。
   *
   * 例子：当前模块 dist/main.js 里 require('./ui/x.js')
   *   -> 目录 dist/ + ui/x.js -> dist/ui/x.js
   * 不以 ./ 开头、也不以 / 开头的请求按包根解析（Node 会去找 node_modules，
   * 包内没有那种东西，按包根更符合直觉）。
   */
  function resolveModulePath(request) {
    if (typeof request !== 'string' || !request) {
      throw new Error('require expects a module path');
    }
    var current = root.__praxis_current_module || '';
    var slash = current.lastIndexOf('/');
    var base = slash >= 0 ? current.substring(0, slash + 1) : '';
    var combined = request.charAt(0) === '/' ? request.substring(1) : base + request;
    var parts = combined.split('/');
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var segment = parts[i];
      if (!segment || segment === '.') {
        continue;
      }
      if (segment === '..') {
        out.pop();
        continue;
      }
      out.push(segment);
    }
    return out.join('/');
  }

  root.require = function (request) {
    var normalized = resolveModulePath(request);
    var candidates = [normalized, normalized + '.js', normalized + '/index.js'];
    var resolved = null;
    var source = null;
    for (var i = 0; i < candidates.length; i++) {
      if (root.__praxis_modules[candidates[i]]) {
        return root.__praxis_modules[candidates[i]].exports;
      }
      if (typeof PraxisHost === 'undefined' || typeof PraxisHost.readFile !== 'function') {
        throw new Error('PraxisHost.readFile is unavailable; cannot require ' + normalized);
      }
      var text = PraxisHost.readFile(candidates[i]);
      if (typeof text === 'string') {
        resolved = candidates[i];
        source = text;
        break;
      }
    }
    if (resolved === null) {
      throw new Error('module not found in package: ' + normalized);
    }
    var module = { exports: {} };
    root.__praxis_modules[resolved] = module;
    var previous = root.__praxis_current_module;
    root.__praxis_current_module = resolved;
    try {
      var factory = new Function('exports', 'module', 'require', source);
      factory(module.exports, module, root.require);
    } finally {
      root.__praxis_current_module = previous;
    }
    return module.exports;
  };

  /**
   * 调用包脚本里的一个函数（子包工具的入口）。
   *
   * @return JSON 字符串：{ ok: true, result } 或 { error }
   */
  /**
   * 渲染一个 compose_dsl 入口。
   *
   * ## 契约（照 JsComposeDslRuntimeScript.__operit_build_compose_response）
   *
   *     entry(ctx) -> { type, props, children, slots }
   *     响应 = { tree, state, memo }
   *
   * entry 取 module.exports.default 或 module.exports.Screen。
   *
   * ## ctx 现在是空的
   *
   * Operit 的 ctx 由 922 行的 JsComposeDslBridge 构造（状态、动作、主题、资源）。
   * Praxis 还没有那套桥，所以这里给空对象 —— 依赖 ctx 的入口会抛错，错误回到
   * 调用方，而不是画出一个假页面。
   *
   * @return JSON 字符串：{ tree, state, memo } 或 { error }
   */
  root.__praxis_render = function (entryId) {
    var screen = root.__praxis_screens[entryId];
    if (typeof screen !== "function") {
      return JSON.stringify({ error: "入口没有可渲染的 screen: " + entryId });
    }
    var bundle = { ctx: {}, state: {}, memo: {} };
    try {
      var tree = screen(bundle.ctx);
      if (tree && typeof tree.then === "function") {
        return JSON.stringify({
          error: "入口返回了 Promise；Praxis 的渲染桥只支持同步返回"
        });
      }
      if (!tree || typeof tree !== "object" || typeof tree.type !== "string") {
        return JSON.stringify({
          error: "入口没有返回渲染树（需要 { type, props, children, slots }）"
        });
      }
      return JSON.stringify({ tree: tree, state: bundle.state, memo: bundle.memo });
    } catch (e) {
      return JSON.stringify({ error: String(e && e.message ? e.message : e) });
    }
  };

  root.__praxis_invoke = function (entryFunction, argsJson) {
    var fn = null;
    if (typeof root[entryFunction] === 'function') {
      fn = root[entryFunction];
    } else if (root.exports && typeof root.exports[entryFunction] === 'function') {
      fn = root.exports[entryFunction];
    }
    if (!fn) {
      return JSON.stringify({ error: 'function not found: ' + entryFunction });
    }
    try {
      var args = {};
      if (typeof argsJson === 'string' && argsJson.trim()) {
        args = JSON.parse(argsJson);
      }
      var out = fn(args);
      return JSON.stringify({ ok: true, result: out === undefined ? null : out });
    } catch (e) {
      return JSON.stringify({ error: String(e && e.message ? e.message : e) });
    }
  };

  /**
   * 执行包注册阶段。
   *
   * @return true 表示执行过 registerToolPkg；false 表示包没导出它。
   */
  root.__praxis_run_registration = function () {
    var entry = null;
    if (typeof root.registerToolPkg === 'function') {
      entry = root.registerToolPkg;
    } else if (typeof root.exports === 'object' && root.exports
        && typeof root.exports.registerToolPkg === 'function') {
      entry = root.exports.registerToolPkg;
    }
    if (!entry) {
      reg.errors.push('main script does not export registerToolPkg()');
      return false;
    }
    entry();
    return true;
  };
})();
""".trimIndent()
}
