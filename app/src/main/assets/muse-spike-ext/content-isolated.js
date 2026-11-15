// Spike 0 ISOLATED world (document_start): JS env probe + forward page results.
function spikeSend(msg) {
  try { console.log("SPIKE-ISO " + msg); } catch (e) {}
  try { browser.runtime.sendNativeMessage("browser", {from: "iso", msg: msg}); } catch (e) {}
}
spikeSend("isolated-ready");
try {
  var env = {
    chrome: typeof window.chrome,
    webdriver: navigator.webdriver,
    plugins: navigator.plugins.length,
    languages: (navigator.languages || []).join(","),
    cores: navigator.hardwareConcurrency,
    ua: navigator.userAgent,
    mainFlag: (typeof window.__museSpikeMain !== "undefined") ? String(window.__museSpikeMain) : "absent"
  };
  spikeSend("jsenv " + JSON.stringify(env));
} catch (e) {
  spikeSend("jsenv-FAILED " + (e && e.message ? e.message : e));
}
window.addEventListener("load", function() {
  setTimeout(function() {
    try {
      var el = document.getElementById("spike-results");
      if (el) spikeSend("page-results " + el.textContent);
      spikeSend("mainFlag-after-load " + ((typeof window.__museSpikeMain !== "undefined") ? String(window.__museSpikeMain) : "absent"));
    } catch (e) { spikeSend("load-check-FAILED " + (e && e.message ? e.message : e)); }
  }, 2000);
});
