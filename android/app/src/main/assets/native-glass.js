(function () {
  "use strict";
  // 由宿主替换这两个 JSON 字符串；跨源文档与 iframe 不得应用 App 的外观覆盖。
  if (window.top !== window || location.origin !== "__MP_GLASS_ORIGIN__") return;
  var styleId = "mpilot-native-glass";
  var css = "__MP_GLASS_CSS__";
  function install() {
    if (!document.documentElement) return false;
    if (!document.getElementById(styleId)) {
      var style = document.createElement("style");
      style.id = styleId;
      style.textContent = css;
      (document.head || document.documentElement).appendChild(style);
    }
    document.documentElement.setAttribute("data-mp-glass", "1");
    return true;
  }
  // document-start 时根节点可能尚未出现；只观察文档直接子节点并在安装后断开。
  if (!install()) {
    var observer = new MutationObserver(function () {
      if (install()) observer.disconnect();
    });
    observer.observe(document, { childList: true });
  }
})();
