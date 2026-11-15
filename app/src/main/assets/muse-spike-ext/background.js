// Spike 0 background: webRequest blocking + cookies probe.
// Logs via console AND native message to the app (dual channel).
var SPIKE_AD_HOSTS = [
  "creative.myavlive.com",
  "creative.mavrtracktor.com",
  "go.mavrtracktor.com",
  "feckfulmisaver.com",
  "ca.ijknabd.top",
  "tq.qwehkg.top"
];
function spikeLog(msg) {
  try { console.log("SPIKE-BG " + msg); } catch (e) {}
  try { browser.runtime.sendNativeMessage("browser", {from: "bg", msg: msg}); } catch (e) {}
}
spikeLog("background-ready");
try {
  browser.webRequest.onBeforeRequest.addListener(
    function(details) {
      try {
        var u = new URL(details.url);
        if (SPIKE_AD_HOSTS.indexOf(u.hostname) !== -1) {
          spikeLog("BLOCKED " + details.url);
          return {cancel: true};
        }
      } catch (e) {}
      return {};
    },
    {urls: ["<all_urls>"]},
    ["blocking"]
  );
  spikeLog("webrequest-listener-registered");
} catch (e) {
  spikeLog("webrequest-listener-FAILED " + (e && e.message ? e.message : e));
}
// cookies API probe (one-off at startup)
function probeCookies(tag) {
  try {
    browser.cookies.getAll({}).then(function(cookies) {
      var names = [];
      for (var i = 0; i < cookies.length; i++) names.push(cookies[i].name);
      spikeLog("cookies-getAll-ok " + tag + " count=" + cookies.length + " names=[" + names.join(",") + "]");
    }, function(err) {
      spikeLog("cookies-getAll-FAILED " + tag + " " + err);
    });
  } catch (e) {
    spikeLog("cookies-API-FAILED " + tag + " " + (e && e.message ? e.message : e));
  }
  // missav.ai scoped probe (cf_clearance lives here)
  try {
    browser.cookies.getAll({domain: "missav.ai"}).then(function(cookies) {
      var names = [];
      for (var i = 0; i < cookies.length; i++) names.push(cookies[i].name);
      spikeLog("cookies-missav " + tag + " [" + names.join(",") + "]");
    }, function(err) {
      spikeLog("cookies-missav-FAILED " + tag + " " + err);
    });
  } catch (e) {
    spikeLog("cookies-missav-API-FAILED " + tag + " " + (e && e.message ? e.message : e));
  }
}
probeCookies("startup");
// periodic re-probe so cf_clearance is caught after MissAV loads
setInterval(function() { probeCookies("periodic"); }, 5000);
