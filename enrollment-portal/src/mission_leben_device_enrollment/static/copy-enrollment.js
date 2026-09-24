(() => {
  const button = document.getElementById("copy-enrollment-link");
  const status = document.getElementById("copy-enrollment-status");
  if (!button || !status) return;

  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(button.dataset.link);
      status.textContent = "Einrichtungslink kopiert. Bitte nur an die vorgesehene Person senden.";
    } catch (_) {
      status.textContent = "Kopieren nicht möglich. Bitte den QR-Code direkt mit dem Gerät scannen.";
    }
  });
})();
