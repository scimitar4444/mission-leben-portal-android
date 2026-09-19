(() => {
  "use strict";

  const button = document.getElementById("continue-enrollment");
  const status = document.getElementById("enrollment-status");
  if (!button || !status) return;

  const parameters = new URLSearchParams(window.location.hash.slice(1));
  const allowed = new Set(["token", "token_id", "mode"]);
  const keys = Array.from(parameters.keys());
  const token = parameters.getAll("token");
  const tokenId = parameters.getAll("token_id");
  const mode = parameters.getAll("mode");
  const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  const valid =
    keys.length === 3 &&
    keys.every((key) => allowed.has(key)) &&
    token.length === 1 &&
    token[0].length >= 20 &&
    token[0].length <= 512 &&
    !/\s/.test(token[0]) &&
    tokenId.length === 1 &&
    uuidPattern.test(tokenId[0]) &&
    mode.length === 1 &&
    (mode[0] === "personal" || mode[0] === "shared");

  if (!valid) {
    status.textContent = "Dieser Einrichtungslink ist unvollständig. Bitte scannen Sie einen neuen QR-Code.";
    status.classList.add("error-text");
    return;
  }

  const target = new URL("de.missionleben.portal://enroll");
  target.searchParams.set("token", token[0]);
  target.searchParams.set("token_id", tokenId[0]);
  target.searchParams.set("mode", mode[0]);
  button.href = target.toString();
  button.classList.remove("disabled");
  button.removeAttribute("aria-disabled");
  status.textContent = "Der einmalige Einrichtungscode ist bereit.";
})();
