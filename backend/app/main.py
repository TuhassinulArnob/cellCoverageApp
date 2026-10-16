import os
from pathlib import Path
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse
from backend.app.api.routes import router

app = FastAPI(
    title="CellScope LTE Analytics Engine",
    description="Backend API for Mobile-First LTE Drive-Test Analytics, RF Diagnostics & Mobility Optimization",
    version="1.0.0",
)

# Enable CORS for local development and PWA access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# API routes
app.include_router(router)

# Mount frontend production build if available
FRONTEND_DIST_DIR = Path(__file__).resolve().parent.parent.parent / "frontend" / "dist"

if FRONTEND_DIST_DIR.exists():
    app.mount("/assets", StaticFiles(directory=FRONTEND_DIST_DIR / "assets"), name="assets")

    @app.get("/{full_path:path}")
    async def serve_frontend(full_path: str):
        # Don't intercept API routes or docs
        if full_path.startswith("api") or full_path.startswith("docs") or full_path.startswith("openapi.json"):
            return None
        file_path = FRONTEND_DIST_DIR / full_path
        if file_path.exists() and file_path.is_file():
            return FileResponse(file_path)
        return FileResponse(FRONTEND_DIST_DIR / "index.html")
else:
    @app.get("/")
    def root():
        return {
            "app": "CellScope",
            "description": "Mobile-First LTE Drive-Test Analytics Platform",
            "docs_url": "/docs",
            "health_url": "/api/health",
        }
