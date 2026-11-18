(function () {
  "use strict";

  window.lrInstallArtwork = function (manifest, baseUrl) {
    if (window.lrSetArtwork) return;

    const root = document.documentElement;
    let mode = null;
    let displayed = null;
    let pending = null;
    let failedFile = null;
    let density = 1;
    let densityTimer = null;

    const style = document.createElement("style");
    style.textContent = `
      html[data-lr-artwork] > body {
        isolation: isolate !important;
        background: var(--lr-bg) !important;
      }
      html[data-lr-artwork] > body > #lr-artwork {
        all: initial !important;
        display: block !important;
        position: fixed !important;
        top: 0 !important;
        left: 0 !important;
        z-index: -1 !important;
        width: 100% !important;
        height: 100% !important;
        object-fit: cover !important;
        opacity: .14 !important;
        -webkit-mask-image: radial-gradient(ellipse at center, rgba(0,0,0,.2) 50%, #000 100%) !important;
        mask-image: radial-gradient(ellipse at center, rgba(0,0,0,.2) 50%, #000 100%) !important;
        pointer-events: none !important;
      }
      html[data-lr-artwork="night"] > body > #lr-artwork {
        opacity: .21 !important;
      }
    `;
    document.head.appendChild(style);

    function selectArtwork() {
      const width = root.clientWidth;
      const height = root.clientHeight;
      density = window.devicePixelRatio || 1;
      const variants = manifest[mode][width >= height * 1.08 ? "wide" : "portrait"];
      // Cover requires both physical axes to fit. Use the largest original tier
      // when the display needs more pixels than the artwork provides.
      return variants.find(function (variant) {
        return variant.width >= width * density && variant.height >= height * density;
      }) || variants[variants.length - 1];
    }

    function updateArtwork() {
      const variant = selectArtwork();
      if (displayed && displayed.file === variant.file) {
        pending = null;
        return;
      }
      if (failedFile && failedFile !== variant.file) failedFile = null;
      if (failedFile === variant.file || (pending && pending.file === variant.file)) return;

      const image = new Image();
      const request = { file: variant.file, image: image };
      image.id = "lr-artwork";
      image.alt = "";
      image.setAttribute("aria-hidden", "true");
      image.decoding = "async";
      pending = request;

      function finish(success) {
        if (pending !== request) return;
        pending = null;
        if (!success) {
          failedFile = request.file;
          return;
        }
        root.setAttribute("data-lr-artwork", mode);
        if (displayed) displayed.image.replaceWith(image);
        else document.body.appendChild(image);
        displayed = request;
      }

      image.onerror = function () { finish(false); };
      image.onload = function () {
        if (image.decode) image.decode().then(function () { finish(true); }, function () { finish(false); });
        else finish(true);
      };
      // The same image is mounted after decoding, so rendering never needs a
      // second request for a separately assigned background URL.
      image.src = baseUrl + variant.file;
    }

    function watchVisibleDensity() {
      window.clearInterval(densityTimer);
      densityTimer = null;
      if (document.visibilityState === "hidden") return;
      updateArtwork();
      // Across monitors, DPR can change without a resize event.
      densityTimer = window.setInterval(function () {
        if (density !== (window.devicePixelRatio || 1)) updateArtwork();
      }, 1000);
    }

    window.lrSetArtwork = function (nextMode) {
      const next = Object.prototype.hasOwnProperty.call(manifest, nextMode) ? nextMode : null;
      if (next === mode) return;
      const wasEnabled = mode !== null;
      mode = next;
      pending = null;
      failedFile = null;
      if (!mode) {
        window.removeEventListener("resize", updateArtwork);
        document.removeEventListener("visibilitychange", watchVisibleDensity);
        window.clearInterval(densityTimer);
        densityTimer = null;
        root.removeAttribute("data-lr-artwork");
        if (displayed) displayed.image.remove();
        displayed = null;
        return;
      }
      if (displayed) root.setAttribute("data-lr-artwork", mode);
      if (wasEnabled) updateArtwork();
      else {
        window.addEventListener("resize", updateArtwork);
        document.addEventListener("visibilitychange", watchVisibleDensity);
        watchVisibleDensity();
      }
    };
  };
})();
