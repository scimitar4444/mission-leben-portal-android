from __future__ import annotations

import logging
from base64 import b64encode
from contextlib import asynccontextmanager
from io import BytesIO
from pathlib import Path
from urllib.parse import urlencode
from zoneinfo import ZoneInfo

import segno
from fastapi import FastAPI, Form, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from jinja2 import Environment, FileSystemLoader, StrictUndefined, select_autoescape
from pydantic import BaseModel, Field

from .auth import Actor, CsrfProtector, actor_from_request, authenticated_actor_from_request
from .authentik import AuthentikClient, AuthentikError
from .config import Settings
from .mailer import send_setup_mail, validate_address
from .service import EnrollmentService, IssuedEnrollment


LOGGER = logging.getLogger("mission_leben_device_enrollment.http")
PACKAGE_ROOT = Path(__file__).parent


class RedeemRequest(BaseModel):
    mode: str
    device_serial: str = Field(min_length=16, max_length=160)
    device_name: str = Field(min_length=2, max_length=120)
    enrollment_profile_supported: bool = False


class SetupPreviewRequest(BaseModel):
    token_uuid: str = Field(min_length=36, max_length=36)
    token: str = Field(min_length=20, max_length=512)
    mode: str


def create_app(
    settings: Settings,
    authentik: AuthentikClient | None = None,
) -> FastAPI:
    client = authentik or AuthentikClient(settings)
    service = EnrollmentService(settings, client)
    csrf = CsrfProtector(settings.csrf_secret, settings.public_origin)
    templates = Environment(
        loader=FileSystemLoader(PACKAGE_ROOT / "templates"),
        autoescape=select_autoescape(["html", "xml"]),
        undefined=StrictUndefined,
    )
    templates.filters["device_seen"] = lambda value: (
        value.astimezone(ZoneInfo(settings.display_timezone)).strftime("%d.%m.%Y, %H:%M Uhr")
        if value is not None
        else "noch keine Meldung"
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await client.close()

    app = FastAPI(
        title="Mission Leben Geräte-Einrichtung",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    app.mount("/static", StaticFiles(directory=PACKAGE_ROOT / "static"), name="static")

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        response = await call_next(request)
        script_policy = (
            "script-src 'self'; "
            if request.url.path in {"/install", "/setup", "/download", "/personal/enrollments", "/shared/enrollments", "/handset/enrollments"}
            else ""
        )
        connect_policy = (
            "connect-src 'self'; "
            if request.url.path in {"/setup", "/personal/enrollments", "/shared/enrollments", "/handset/enrollments"}
            else ""
        )
        response.headers["Cache-Control"] = "no-store"
        response.headers["Pragma"] = "no-cache"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        # Some privacy-focused WebViews omit Origin on same-origin form posts.
        # Keep a same-origin Referer as the CSRF verifier's safe fallback while
        # still preventing the portal URL from leaking to other origins.
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        response.headers["Content-Security-Policy"] = (
            f"default-src 'none'; style-src 'self'; {script_policy}{connect_policy}img-src 'self' data:; "
            "font-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"
        )
        return response

    def actor(request: Request) -> Actor:
        return actor_from_request(request, settings)

    def authenticated_actor(request: Request) -> Actor:
        return authenticated_actor_from_request(request, settings)

    def html(name: str, status: int = 200, **context) -> HTMLResponse:
        return HTMLResponse(templates.get_template(name).render(**context), status_code=status)

    def page_context(current: Actor, action: str = "") -> dict:
        return {
            "actor": current,
            "csrf_token": csrf.issue(current, action) if action else "",
        }

    def download_qr_svg() -> str:
        qr = segno.make(settings.apk_download_url, error="m")
        return qr.svg_inline(scale=5, border=2, dark="#5b1438", light="#ffffff")

    def setup_page(enrollment: IssuedEnrollment, current: Actor, mail_status: str = "") -> HTMLResponse:
        return html(
            "setup.html",
            setup_link=enrollment.setup_link(settings.public_origin),
            target_label=enrollment.target_label,
            replaced_devices=enrollment.replaced_devices,
            download_qr_svg=download_qr_svg(),
            apk_download_url=settings.apk_download_url,
            self_service=False,
            mail_status=mail_status,
            **page_context(current),
        )

    def require_it_mail(current: Actor) -> None:
        if not current.can_initialize_shared_handset:
            raise HTTPException(403, "Nur die IT darf Einrichtungslinks per E-Mail versenden.")
        if not settings.smtp_host or not settings.smtp_sender:
            raise HTTPException(503, "Der E-Mail-Versand ist noch nicht eingerichtet.")

    def mail_recipient(value: str, *, corporate_only: bool = False) -> str:
        try:
            return validate_address(value, corporate_only=corporate_only)
        except ValueError as error:
            raise HTTPException(400, str(error)) from error

    def first_name(user: dict) -> str:
        raw = str(user.get("name") or "")
        name = " ".join((raw.split(",", 1)[1] if "," in raw else raw).split())
        return name.split()[0] if name else ""

    async def mail_enrollment(
        enrollment: IssuedEnrollment, recipient: str, greeting: str, *, email_only: bool = False
    ) -> str:
        salutation = f"Hallo {greeting}," if greeting else "Hallo,"
        purpose = (
            f"ich habe die Einrichtung eines Diensthandys für das Gruppenkonto {enrollment.target_label} vorbereitet. "
            if email_only else
            "ich habe die Einrichtung von Mission Leben Zentral für dich vorbereitet. "
        )
        connection = (
            "Der zweite QR-Code verbindet das Diensthandy mit dem ausgewählten Gruppenkonto.\n\n"
            if email_only else
            "Der zweite QR-Code verbindet das Gerät mit deinem Zugang.\n\n"
        )
        body = (
            f"{salutation}\n\n"
            f"{purpose}"
            "Öffne den folgenden Link an einem PC. Auf der Seite bereitest du zuerst dein Android- oder Samsung-Gerät vor, "
            "installierst die App mit dem ersten QR-Code und klickst dann auf „App installiert“. "
            f"{connection}"
            f"{enrollment.setup_link(settings.public_origin)}\n\n"
            "Der Link gilt 30 Minuten ab seiner Erstellung und kann nur einmal zur Registrierung verwendet werden. "
            "Bitte leite ihn nicht weiter. Falls du keine Einrichtung erwartest, melde dich beim IT-Service.\n\n"
            "Viele Grüße\nDein IT-Service\n"
        )
        try:
            await send_setup_mail(settings, recipient, "Mission Leben Zentral: Gerät einrichten", body)
        except Exception:
            LOGGER.exception("setup mail delivery failed")
            if email_only:
                raise HTTPException(502, "Die E-Mail konnte nicht versendet werden. Bitte später erneut versuchen.")
            return "E-Mail-Versand fehlgeschlagen. Bitte den Einrichtungslink selbst kopieren und sicher weitergeben."
        return f"Einrichtungslink an {recipient} versendet."

    def download_url() -> str:
        return settings.public_origin.rstrip("/") + "/download"

    def install_qr_svg() -> str:
        qr = segno.make(settings.apk_download_url, error="m")
        return qr.svg_inline(scale=3, border=2, dark="#5b1438", light="#ffffff")

    @app.exception_handler(AuthentikError)
    async def authentik_error(request: Request, error: AuthentikError):
        status = error.status if 400 <= error.status < 500 else 502
        if request.url.path.startswith("/api/"):
            return JSONResponse({"error": str(error)}, status_code=status)
        return html("error.html", status=status, title="Einrichtung nicht möglich", message=str(error))

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, error: HTTPException):
        if request.url.path.startswith("/api/"):
            return JSONResponse({"error": str(error.detail)}, status_code=error.status_code)
        return html(
            "error.html",
            status=error.status_code,
            title="Zugriff nicht möglich",
            message=str(error.detail),
        )

    @app.get("/healthz")
    async def healthz():
        return {"status": "ok"}

    @app.get("/install", response_class=HTMLResponse)
    async def install():
        return html("install.html", apk_download_url=settings.apk_download_url)

    @app.get("/setup", response_class=HTMLResponse)
    async def setup():
        return html(
            "setup.html",
            setup_link="",
            target_label="",
            replaced_devices=(),
            download_qr_svg=download_qr_svg(),
            apk_download_url=settings.apk_download_url,
            self_service=False,
            mail_status="",
        )

    @app.get("/download", response_class=HTMLResponse)
    async def download():
        return html(
            "setup.html",
            setup_link="",
            target_label="",
            replaced_devices=(),
            download_qr_svg=download_qr_svg(),
            apk_download_url=settings.apk_download_url,
            self_service=True,
            mail_status="",
        )

    @app.post("/api/v1/setup/qr")
    async def setup_qr(payload: SetupPreviewRequest):
        if any(character.isspace() for character in payload.token):
            raise HTTPException(400, "Ungültiger Registrierungscode.")
        expires = await service.validate_setup_token(payload.token_uuid, payload.token, payload.mode)
        enrollment_link = settings.public_origin.rstrip("/") + "/install#" + urlencode(
            {"token": payload.token, "token_id": payload.token_uuid, "mode": payload.mode}
        )
        qr = segno.make(enrollment_link, error="m")
        image = BytesIO()
        qr.save(image, kind="png", scale=5, border=2)
        return JSONResponse({
            "image": "data:image/png;base64," + b64encode(image.getvalue()).decode("ascii"),
            "expires": expires.astimezone(ZoneInfo(settings.display_timezone)).strftime("%H:%M Uhr"),
            "expires_at": expires.isoformat(),
        })

    @app.get("/.well-known/assetlinks.json")
    async def asset_links():
        if not settings.android_cert_sha256_fingerprints:
            raise HTTPException(503, "Android-App-Link ist noch nicht konfiguriert.")
        return JSONResponse(
            [
                {
                    "relation": ["delegate_permission/common.handle_all_urls"],
                    "target": {
                        "namespace": "android_app",
                        "package_name": "de.missionleben.portal",
                        "sha256_cert_fingerprints": list(
                            settings.android_cert_sha256_fingerprints
                        ),
                    },
                }
            ]
        )

    @app.get("/", response_class=HTMLResponse)
    async def home(request: Request):
        current = actor(request)
        return html(
            "home.html",
            install_url=download_url(),
            install_qr_svg=install_qr_svg(),
            **page_context(current),
        )

    @app.get("/self", response_class=HTMLResponse)
    async def self_enrollment(request: Request):
        current = authenticated_actor(request)
        return html(
            "self.html",
            existing_devices=await service.personal_devices_for_username(current.username),
            token_ttl_minutes=settings.token_ttl_seconds // 60,
            **page_context(current, "issue-self-personal"),
        )

    @app.post("/self/enrollments", response_class=HTMLResponse)
    async def issue_self_enrollment(
        request: Request,
        csrf_token: str = Form(...),
    ):
        current = authenticated_actor(request)
        csrf.verify_request(request, current, "issue-self-personal", csrf_token)
        enrollment = await service.issue_self_personal(current)
        # A browser may silently reject an automatic custom-scheme redirect
        # after a form POST. Render a confirmation page instead so opening the
        # app is an explicit user gesture and works in Chrome and our WebView.
        return html(
            "self_ready.html",
            enrollment=enrollment,
            app_link=enrollment.deep_link(),
            fallback_link=enrollment.install_link(settings.public_origin),
            expires_local=enrollment.expires.astimezone(
                ZoneInfo(settings.display_timezone)
            ).strftime("%H:%M Uhr"),
            **page_context(current),
        )

    @app.get("/personal", response_class=HTMLResponse)
    async def personal(request: Request, q: str = ""):
        current = actor(request)
        results = await service.employees_for(current, q) if len(q.strip()) >= 2 else []
        return html(
            "personal.html",
            query=q.strip(),
            employees=results,
            can_send_mail=current.can_initialize_shared_handset and bool(settings.smtp_host),
            **page_context(current, "issue-personal"),
        )

    @app.get("/download/send", response_class=HTMLResponse)
    async def download_send(request: Request, q: str = ""):
        current = actor(request)
        require_it_mail(current)
        results = await client.employees(q, None) if len(q.strip()) >= 2 else []
        return html(
            "download_send.html",
            query=q.strip(),
            employees=results,
            **page_context(current, "send-personal-download"),
        )

    @app.post("/personal/enrollments", response_class=HTMLResponse)
    async def issue_personal(
        request: Request,
        employee_pk: int = Form(...),
        csrf_token: str = Form(...),
        delivery: str = Form("screen"),
    ):
        current = actor(request)
        csrf.verify_request(request, current, "issue-personal", csrf_token)
        if delivery not in {"screen", "email"}:
            raise HTTPException(400, "Ungültiger Versandweg.")
        recipient = ""
        user = None
        if delivery == "email":
            require_it_mail(current)
            user = await client.employee(employee_pk)
            recipient = mail_recipient(str(user.get("email") or ""))
        enrollment = await service.issue_personal(current, employee_pk)
        mail_status = await mail_enrollment(enrollment, recipient, first_name(user)) if user else ""
        return setup_page(enrollment, current, mail_status)

    @app.post("/personal/download-email", response_class=HTMLResponse)
    async def mail_personal_download(
        request: Request,
        employee_pk: int = Form(...),
        csrf_token: str = Form(...),
    ):
        current = actor(request)
        csrf.verify_request(request, current, "send-personal-download", csrf_token)
        require_it_mail(current)
        user = await client.employee(employee_pk)
        recipient = mail_recipient(str(user.get("email") or ""))
        name = first_name(user)
        salutation = f"Hallo {name}," if name else "Hallo,"
        body = (
            f"{salutation}\n\n"
            "hier findest du Mission Leben Zentral für dein persönliches Android-Handy:\n\n"
            f"{download_url()}\n\n"
            "Öffne den Link an einem PC. Bereite dein Handy wie beschrieben vor, scanne den Download-QR-Code und installiere die App. "
            "Danach klicke auf „App installiert“, öffne die App und melde dich mit deinem Benutzernamen, Passwort und vorhandenen TOTP an. "
            "Die App führt dich durch die Registrierung deines Handys. Dieser reine Installationslink läuft nicht ab und enthält keinen Registrierungscode.\n\n"
            "Falls du Fragen hast, melde dich beim IT-Service.\n\nViele Grüße\nDein IT-Service\n"
        )
        try:
            await send_setup_mail(settings, recipient, "Mission Leben Zentral: App selbst installieren", body)
        except Exception:
            LOGGER.exception("download mail delivery failed")
            raise HTTPException(502, "Die E-Mail konnte nicht versendet werden. Bitte später erneut versuchen.")
        return html("mail_sent.html", recipient=recipient, **page_context(current))

    @app.get("/handset", response_class=HTMLResponse)
    async def handset(request: Request, q: str = ""):
        current = actor(request)
        require_it_mail(current)
        accounts = await service.shared_handset_accounts_for(current, q) if len(q.strip()) >= 2 else []
        return html(
            "handset.html",
            query=q.strip(),
            accounts=accounts,
            **page_context(current),
        )

    @app.get("/handset/recipient", response_class=HTMLResponse)
    async def handset_recipient(request: Request, account_pk: int, q: str = ""):
        current = actor(request)
        require_it_mail(current)
        account = await service.shared_handset_account_for(current, account_pk)
        people = await client.employees(q, None) if len(q.strip()) >= 2 else []
        recipients = []
        for person in people:
            try:
                mail_recipient(str(person.get("email") or ""), corporate_only=True)
            except HTTPException:
                continue
            recipients.append(person)
        return html(
            "handset_recipient.html",
            account=account,
            query=q.strip(),
            recipients=recipients,
            **page_context(current, "issue-shared-handset"),
        )

    @app.post("/handset/enrollments", response_class=HTMLResponse)
    async def issue_handset(
        request: Request,
        account_pk: int = Form(...),
        recipient_pk: int = Form(...),
        csrf_token: str = Form(...),
    ):
        current = actor(request)
        csrf.verify_request(request, current, "issue-shared-handset", csrf_token)
        require_it_mail(current)
        person = await client.employee(recipient_pk)
        recipient = mail_recipient(str(person.get("email") or ""), corporate_only=True)
        enrollment = await service.issue_shared_handset(current, account_pk)
        await mail_enrollment(enrollment, recipient, first_name(person), email_only=True)
        return html("enrollment_sent.html", recipient=recipient, **page_context(current))

    @app.get("/shared", response_class=HTMLResponse)
    async def shared(request: Request):
        current = actor(request)
        organizations = await service.organizations_for(current)
        return html(
            "shared.html",
            organizations=organizations,
            **page_context(current, "issue-shared"),
        )

    @app.post("/shared/enrollments", response_class=HTMLResponse)
    async def issue_shared(
        request: Request,
        organization_uuid: str = Form(...),
        device_label: str = Form(...),
        csrf_token: str = Form(...),
    ):
        current = actor(request)
        csrf.verify_request(request, current, "issue-shared", csrf_token)
        enrollment = await service.issue_shared(current, organization_uuid, device_label)
        return setup_page(enrollment, current)

    @app.post("/api/v1/enrollments/{token_uuid}/redeem")
    async def redeem(
        token_uuid: str,
        payload: RedeemRequest,
        authorization: str = Header(default=""),
    ):
        if not authorization.startswith("Bearer "):
            raise HTTPException(401, "Registrierungscode fehlt.")
        token = authorization.removeprefix("Bearer ").strip()
        if not 20 <= len(token) <= 512 or any(character.isspace() for character in token):
            raise HTTPException(401, "Registrierungscode ist ungültig.")
        result = await service.redeem(
            token_uuid,
            token,
            payload.mode,
            payload.device_serial,
            payload.device_name,
            payload.enrollment_profile_supported,
        )
        return JSONResponse(result)

    @app.get("/api/v1/devices/status")
    async def device_status(authorization: str = Header(default="")):
        if not authorization.startswith("Bearer+Agent "):
            raise HTTPException(401, "Geräteschlüssel fehlt.")
        token = authorization.removeprefix("Bearer+Agent ").strip()
        if not 20 <= len(token) <= 2048 or any(character.isspace() for character in token):
            raise HTTPException(401, "Geräteschlüssel ist ungültig.")
        return JSONResponse(await service.device_status(token))

    return app


def create_app_from_env() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    return create_app(Settings.from_env())
