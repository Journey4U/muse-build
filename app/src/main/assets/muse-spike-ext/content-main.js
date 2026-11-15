// Spike 0 MAIN world (document_start): runs before any page script.
// NOTE: MAIN world has NO browser.* APIs. Log via console only + window flags.
try {
  window.__museSpikeMain = true;
  var RealWS = window.WebSocket;
  window.WebSocket = function(url, protocols) {
    try { console.log("SPIKE-MAIN ws-open-attempt " + url); } catch (e) {}
    if (typeof url === "string" && (url.indexOf("ijknabd") !== -1 || url.indexOf("jqygnbfq") !== -1 || url.indexOf("dqgjla") !== -1 || url.indexOf("qwehkg") !== -1)) {
      try { console.log("SPIKE-MAIN ws-BLOCKED " + url); } catch (e) {}
      throw new Error("Spike: WebSocket to ad domain blocked");
    }
    return new RealWS(url, protocols);
  };
  window.WebSocket.prototype = RealWS.prototype;
  try { console.log("SPIKE-MAIN ready, WebSocket replaced"); } catch (e) {}
} catch (e) {
  try { console.log("SPIKE-MAIN FAILED " + (e && e.message ? e.message : e)); } catch (e2) {}
}
