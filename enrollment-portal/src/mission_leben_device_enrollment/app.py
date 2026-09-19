from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path
from zoneinfo import ZoneInfo

import segno
from fastapi import FastAPI, Form, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from jinja2 import Environment, FileSystemLoader, StrictUndefined, select_autoescape
from pydantic import BaseModel, Field

from .auth import Actor, CsrfProtector, actor_from_request, authenticated_actor_from_request
from .authentik import AuthentikClient, AuthentikError
from .config import Settings
from .service import EnrollmentService, IssuedEnrollment


LOGGER = logging.getLogger("mission_leben_device_enrollment.http")
PACKAGE_ROOT = Path(__file__).parent


class RedeemRequest(BaseModel):
    mode: str
    device_serial: str = Field(min_length=16, max_length=160)
    device_name: str = Field(min_length=2, max_length=120)


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
        script_policy = "script-src 'self'; " if request.url.path == "/install" else ""
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
            f"default-src 'none'; style-src 'self'; {script_policy}img-src 'self' data:; "
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

    def qr_svg(enrollment: IssuedEnrollment) -> str:
        qr = segno.make(enrollment.install_link(settings.public_origin), error="m")
        return qr.svg_inline(scale=5, border=2, dark="#5b1438", light="#ffffff")

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
        return html("home.html", **page_context(current))

    @app.get("/self", response_class=HTMLResponse)
    async def self_enrollment(request: Request):
        current = authenticated_actor(request)
        return html(
            "self.html",
            **page_context(current, "issue-self-personal"),
        )

    @app.post("/self/enrollments")
    async def issue_self_enrollment(
        request: Request,
        csrf_token: str = Form(...),
    ):
        current = authenticated_actor(request)
        csrf.verify_request(request, current, "issue-self-personal", csrf_token)
        enrollment = await service.issue_self_personal(current)
        return RedirectResponse(enrollment.deep_link(), status_code=303)

    @app.get("/personal", response_class=HTMLResponse)
    async def personal(request: Request, q: str = ""):
        current = actor(request)
        results = await service.employees_for(current, q) if len(q.strip()) >= 2 else []
        return html(
            "personal.html",
            query=q.strip(),
            employees=results,
            **page_context(current, "issue-personal"),
        )

    @app.post("/personal/enrollments", response_class=HTMLResponse)
    async def issue_personal(
        request: Request,
        employee_pk: int = Form(...),
        csrf_token: str = Form(...),
    ):
        current = actor(request)
        csrf.verify_request(request, current, "issue-personal", csrf_token)
        enrollment = await service.issue_personal(current, employee_pk)
        return html(
            "qr.html",
            enrollment=enrollment,
            qr_svg=qr_svg(enrollment),
            expires_local=enrollment.expires.astimezone(ZoneInfo(settings.display_timezone)).strftime("%H:%M Uhr"),
            **page_context(current),
        )

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
        return html(
            "qr.html",
            enrollment=enrollment,
            qr_svg=qr_svg(enrollment),
            expires_local=enrollment.expires.astimezone(ZoneInfo(settings.display_timezone)).strftime("%H:%M Uhr"),
            **page_context(current),
        )

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
        )
        return JSONResponse(result)

    return app


def create_app_from_env() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    return create_app(Settings.from_env())
