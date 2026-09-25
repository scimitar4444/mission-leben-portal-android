(() => {
  const page = document.getElementById("setup-page");
  const button = document.getElementById("show-registration");
  const step = document.getElementById("registration-step");
  const image = document.getElementById("registration-qr");
  const expiry = document.getElementById("registration-expiry");
  const status = document.getElementById("setup-status");
  const copy = document.getElementById("copy-enrollment-link");
  const selfService = page.dataset.selfService === "true";
  if (selfService) {
    if (copy) {
      copy.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(location.origin + "/download");
          status.textContent = "Installationslink kopiert.";
        } catch (_) {
          status.textContent = "Kopieren nicht möglich. Bitte die Adresse dieser Seite weitergeben.";
        }
      });
    }
    button.addEventListener("click", () => {
      step.hidden = false;
      button.disabled = true;
      status.textContent = "Öffne jetzt die App und melde dich mit deinem TOTP an.";
    });
    return;
  }
  const link = page.dataset.link || (location.hash ? location.origin + "/setup" + location.hash : "");
  if (location.hash) history.replaceState(null, "", "/setup");

  if (copy && link) {
    copy.hidden = false;
    copy.dataset.link = link;
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(link);
        status.textContent = "Einrichtungslink kopiert. Bitte nur an die vorgesehene Person senden.";
      } catch (_) {
        status.textContent = "Kopieren nicht möglich. Bitte den PC-Browser verwenden.";
      }
    });
  }
  if (!link) {
    button.disabled = true;
    status.textContent = "Kein Einrichtungslink vorhanden. Bitte einen neuen Link anfordern.";
    return;
  }
  const fields = new URL(link).hash.slice(1);
  const params = new URLSearchParams(fields);
  const token = params.get("token");
  const token_uuid = params.get("token_id");
  const mode = params.get("mode");
  if (!token || !/^[0-9a-f-]{36}$/i.test(token_uuid || "") || !["personal", "shared"].includes(mode)) {
    button.disabled = true;
    status.textContent = "Der Einrichtungslink ist ungültig. Bitte einen neuen anfordern.";
    return;
  }
  button.addEventListener("click", async () => {
    button.disabled = true;
    status.textContent = "Registrierungs-QR-Code wird geprüft …";
    try {
      const response = await fetch("/api/v1/setup/qr", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({token_uuid, token, mode}),
        cache: "no-store",
      });
      if (!response.ok) throw new Error("Der Link ist abgelaufen oder wurde bereits verwendet. Bitte einen neuen anfordern.");
      const result = await response.json();
      image.src = result.image;
      image.hidden = false;
      expiry.textContent = "Gültig bis " + result.expires + ". Danach bitte einen neuen Link anfordern.";
      expiry.hidden = false;
      step.hidden = false;
      status.textContent = "Jetzt den zweiten QR-Code mit dem Handy scannen.";
      const remaining = Date.parse(result.expires_at) - Date.now();
      setTimeout(() => {
        image.hidden = true;
        image.removeAttribute("src");
        expiry.textContent = "Der Link ist abgelaufen. Bitte einen neuen anfordern.";
        status.textContent = expiry.textContent;
      }, Math.max(0, remaining));
    } catch (error) {
      status.textContent = error.message;
      button.disabled = false;
    }
  });
})();
