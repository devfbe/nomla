// Nomla website: language switch and the hero's voice trace. No dependencies.
(function () {
  "use strict";

  var root = document.documentElement;

  // ----- language -----
  function stored() {
    try { return localStorage.getItem("nomla-lang"); } catch (e) { return null; }
  }
  function setLang(lang, remember) {
    root.setAttribute("data-ui", lang);
    root.lang = lang;
    var title = root.getAttribute("data-title-" + lang);
    if (title) document.title = title;
    document.querySelectorAll("[data-set-lang]").forEach(function (b) {
      b.setAttribute("aria-pressed", String(b.getAttribute("data-set-lang") === lang));
    });
    if (remember) { try { localStorage.setItem("nomla-lang", lang); } catch (e) { /* private mode */ } }
  }
  var initial = stored() || ((navigator.language || "de").toLowerCase().indexOf("de") === 0 ? "de" : "en");
  setLang(initial, false);
  document.querySelectorAll("[data-set-lang]").forEach(function (b) {
    b.addEventListener("click", function () { setLang(b.getAttribute("data-set-lang"), true); });
  });

  // ----- voice trace: noisy on the left, clean on the right -----
  var canvas = document.getElementById("trace");
  if (!canvas || !canvas.getContext) return;
  var ctx = canvas.getContext("2d");
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  // deterministic noise so the picture is the same on every load
  var seed = 7;
  function rnd() { seed = (seed * 16807) % 2147483647; return seed / 2147483647 - 0.5; }
  var noise = [];
  for (var i = 0; i < 4096; i++) noise.push(rnd());

  // syllables: [position, height]; the same speech runs through the whole trace
  var syllables = [[0.06, 0.7], [0.15, 1], [0.24, 0.55], [0.36, 0.85], [0.47, 0.6],
                   [0.58, 0.95], [0.69, 0.7], [0.78, 1], [0.88, 0.6], [0.95, 0.8]];
  function sample(t) {
    var env = 0;
    for (var s = 0; s < syllables.length; s++) {
      var d = (t - syllables[s][0]) / 0.03;
      env += syllables[s][1] * Math.exp(-d * d);
    }
    var voice = Math.sin(t * 760) * 0.62 + Math.sin(t * 1521 + 1) * 0.26 + Math.sin(t * 2283 + 2) * 0.12;
    var cleanFrom = 0.4, cleanTo = 0.6;              // where the chain has done its work
    var k = Math.min(1, Math.max(0, (t - cleanFrom) / (cleanTo - cleanFrom)));
    var dirt = (1 - k) * (noise[Math.floor(t * 4095)] * 0.7 + 0.2 * Math.sin(t * 61));
    return env * voice * 0.95 + dirt;
  }

  function draw(progress) {
    var dpr = window.devicePixelRatio || 1;
    var w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    var grad = ctx.createLinearGradient(0, 0, w, 0);
    grad.addColorStop(0, "rgba(255,255,255,0.55)");
    grad.addColorStop(0.45, "rgba(255,255,255,0.75)");
    grad.addColorStop(0.62, "#F5B83D");
    grad.addColorStop(1, "#F5B83D");
    ctx.strokeStyle = grad;
    ctx.lineWidth = 2;
    ctx.lineJoin = "round";
    ctx.beginPath();
    var end = Math.max(1, Math.floor(w * progress));
    for (var x = 0; x <= end; x++) {
      var y = h / 2 - sample(x / w) * h * 0.42;
      if (x === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();
  }

  if (reduce) {
    draw(1);
  } else {
    var start = null;
    var step = function (ts) {
      if (start === null) start = ts;
      var p = Math.min(1, (ts - start) / 1600);
      draw(1 - Math.pow(1 - p, 3));
      if (p < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
    // background tabs throttle animation frames; make sure the trace ends complete
    setTimeout(function () { draw(1); }, 1800);
  }
  window.addEventListener("resize", function () { draw(1); });
})();
