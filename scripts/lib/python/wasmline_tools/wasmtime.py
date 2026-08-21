"""Download and install Wasmtime C API archives."""

from __future__ import annotations

import json
import os
import shutil
import stat
import tarfile
import tempfile
import zipfile
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from threading import Lock
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener

from .manifest import version as manifest_version
from .output import Console
from .paths import PLATFORMS_ROOT
from .targets import ENGINES, Target, load_targets, target_by_id


REPOSITORY = "crowforkotlin/wasmtime"
API_ROOT = f"https://api.github.com/repos/{REPOSITORY}"


@dataclass(frozen=True)
class Job:
    target: Target
    engine: str
    asset_name: str
    url: str
    size: int


def _format_bytes(value: int) -> str:
    amount = float(value)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if amount < 1024 or unit == "GiB":
            return f"{int(amount)} {unit}" if unit == "B" else f"{amount:.1f} {unit}"
        amount /= 1024
    raise AssertionError("unreachable")


class _DownloadProgress:
    BAR_WIDTH = 24

    def __init__(self, console: Console, jobs: list[Job]) -> None:
        self.console = console
        self.total_files = len(jobs)
        self.total_bytes = sum(job.size for job in jobs) if all(job.size > 0 for job in jobs) else 0
        self.completed_files = 0
        self.downloaded_bytes = 0
        self._last_percent = -1
        self._lock = Lock()
        self._render(force=True)

    def advance(self, amount: int) -> None:
        with self._lock:
            self.downloaded_bytes += amount
            self._render()

    def complete_file(self) -> None:
        with self._lock:
            self.completed_files += 1
            self._render(force=True)

    def close(self) -> None:
        self.console.end_progress()

    def _render(self, *, force: bool = False) -> None:
        if self.total_bytes > 0:
            fraction = min(self.downloaded_bytes / self.total_bytes, 1.0)
        else:
            fraction = self.completed_files / self.total_files
        complete = self.completed_files == self.total_files
        percent = 100 if complete else min(int(fraction * 100), 99)
        if not force and percent == self._last_percent:
            return
        self._last_percent = percent
        filled = self.BAR_WIDTH if complete else min(
            int(fraction * self.BAR_WIDTH), self.BAR_WIDTH - 1
        )
        bar = f"[{'#' * filled}{'-' * (self.BAR_WIDTH - filled)}]"
        transferred = ""
        if self.total_bytes > 0:
            transferred = (
                f"  {_format_bytes(min(self.downloaded_bytes, self.total_bytes))}"
                f"/{_format_bytes(self.total_bytes)}"
            )
        self.console.progress(
            "Download",
            f"{bar} {percent:3d}%  {self.completed_files}/{self.total_files}{transferred}",
        )


def normalize_version(value: str) -> str:
    normalized = value.removeprefix("release-v").removeprefix("v")
    allowed = "0123456789.-+abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    if not normalized or any(character not in allowed for character in normalized):
        raise RuntimeError(f"Invalid Wasmtime version: {value}")
    return normalized


def expected_asset_name(target: Target, engine: str, version: str) -> str:
    engine_part = "-pulley" if engine == "pulley" else ""
    extension = "zip" if target.install_path.startswith("windows/") else "tar.gz"
    return f"wasmtime-v{version}-{target.asset}{engine_part}-min-c-api.{extension}"


def _opener(proxy: str | None):
    if not proxy:
        return build_opener()
    address = proxy if "://" in proxy else f"http://{proxy}"
    return build_opener(ProxyHandler({"http": address, "https": address}))


def _request_json(url: str, proxy: str | None) -> dict:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "wasmline-repository-tool",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(url, headers=headers)
    try:
        with _opener(proxy).open(request, timeout=30) as response:
            value = json.loads(response.read())
    except (HTTPError, URLError, OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Cannot read GitHub release metadata: {error}") from error
    if not isinstance(value, dict):
        raise RuntimeError("GitHub release metadata must be a JSON object.")
    return value


def _download(
    job: Job,
    proxy: str | None,
    on_progress: Callable[[int], None] | None = None,
) -> tuple[Job, Path, int]:
    temporary_dir = Path(tempfile.mkdtemp(prefix=".wasmtime-download-", dir=PLATFORMS_ROOT))
    archive = temporary_dir / job.asset_name
    request = Request(job.url, headers={"User-Agent": "wasmline-repository-tool"})
    try:
        with _opener(proxy).open(request, timeout=60) as response, archive.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                if on_progress is not None:
                    on_progress(len(chunk))
    except Exception:
        shutil.rmtree(temporary_dir, ignore_errors=True)
        raise
    return job, archive, archive.stat().st_size


def _validate_archive_path(name: str) -> None:
    path = PurePosixPath(name.replace("\\", "/"))
    if path.is_absolute() or ".." in path.parts:
        raise RuntimeError(f"Archive contains an unsafe path: {name}")


def _extract(archive: Path, destination: Path) -> None:
    if archive.suffix == ".zip":
        with zipfile.ZipFile(archive) as source:
            for member in source.infolist():
                _validate_archive_path(member.filename)
                mode = member.external_attr >> 16
                if stat.S_ISLNK(mode):
                    raise RuntimeError(f"Archive contains a link: {member.filename}")
            source.extractall(destination)
        return

    with tarfile.open(archive) as source:
        for member in source.getmembers():
            _validate_archive_path(member.name)
            if not member.isfile() and not member.isdir():
                raise RuntimeError(f"Archive contains an unsupported entry: {member.name}")
        source.extractall(destination)


def _content_root(extracted: Path) -> Path:
    include_directories = sorted(path for path in extracted.rglob("include") if path.is_dir())
    for include in include_directories:
        parent = include.parent
        if parent.name == "min" and (parent / "lib").is_dir():
            return parent
    for include in include_directories:
        parent = include.parent
        if (parent / "lib").is_dir():
            return parent
    raise RuntimeError("Archive does not contain matching include and lib directories.")


def _is_installed(target: Target, engine: str, tag: str) -> bool:
    root = PLATFORMS_ROOT / tag / engine / target.install_path
    return (root / "include" / "wasmtime.h").is_file() and (root / "lib" / "libwasmtime.a").is_file()


def _install(job: Job, archive: Path, tag: str) -> None:
    destination = PLATFORMS_ROOT / tag / job.engine / job.target.install_path
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".wasmtime-extract-", dir=PLATFORMS_ROOT) as temporary:
        extracted = Path(temporary)
        _extract(archive, extracted)
        content = _content_root(extracted)
        staging = Path(tempfile.mkdtemp(prefix=f".{destination.name}-", dir=destination.parent))
        try:
            shutil.copytree(content / "include", staging / "include")
            shutil.copytree(content / "lib", staging / "lib")
            backup_root = Path(
                tempfile.mkdtemp(prefix=f".{destination.name}-backup-", dir=destination.parent)
            )
            backup = backup_root / "previous"
            had_destination = destination.exists() or destination.is_symlink()
            remove_backup = True
            try:
                if had_destination:
                    destination.replace(backup)
                try:
                    staging.replace(destination)
                except Exception as install_error:
                    if had_destination:
                        try:
                            backup.replace(destination)
                        except Exception as restore_error:
                            remove_backup = False
                            raise RuntimeError(
                                f"Cannot restore previous files; backup kept at {backup}."
                            ) from restore_error
                    raise install_error
            finally:
                if remove_backup:
                    shutil.rmtree(backup_root, ignore_errors=True)
        finally:
            if staging.exists():
                shutil.rmtree(staging, ignore_errors=True)


def _selected_targets(target_id: str) -> tuple[Target, ...]:
    return load_targets() if target_id == "all" else (target_by_id(target_id),)


def _selected_pairs(target_id: str, engine: str) -> list[tuple[Target, str]]:
    targets = _selected_targets(target_id)
    if target_id != "all" and engine != "all" and engine not in targets[0].engines:
        raise RuntimeError(f"Target {target_id} does not provide the {engine} engine.")
    return [
        (target, selected_engine)
        for target in targets
        for selected_engine in target.engines
        if engine == "all" or selected_engine == engine
    ]


def list_target_rows(console: Console) -> None:
    print(f"{'TARGET':<24} ENGINES", file=console.stream)
    for target in load_targets():
        print(f"{target.id:<24} {', '.join(target.engines)}", file=console.stream)


def download(
    *,
    target_id: str,
    engine: str,
    jobs: int | None,
    proxy: str | None,
    force: bool,
) -> int:
    if engine not in (*ENGINES, "all"):
        raise RuntimeError(f"Unknown engine: {engine}")
    if jobs is not None and jobs < 1:
        raise RuntimeError("--jobs must be greater than zero.")

    selected_version = normalize_version(manifest_version("wasmtime_version"))
    tag = f"release-v{selected_version}"
    pairs = _selected_pairs(target_id, engine)
    PLATFORMS_ROOT.mkdir(parents=True, exist_ok=True)
    console = Console()
    console.info("Version", tag)
    console.info("Target", target_id)
    console.info("Engine", engine)
    console.info("Archives", str(len(pairs)))

    pending_pairs = [pair for pair in pairs if force or not _is_installed(*pair, tag)]
    existing = len(pairs) - len(pending_pairs)
    if not pending_pairs:
        console.ok("Install", f"{len(pairs)}/{len(pairs)} already present under build/platforms/{tag}.")
        return 0

    release = _request_json(f"{API_ROOT}/releases/tags/{tag}", proxy)
    if release.get("tag_name") != tag:
        raise RuntimeError(f"GitHub returned a different release tag for {tag}.")
    raw_assets = release.get("assets")
    if not isinstance(raw_assets, list):
        raise RuntimeError(f"Release {tag} does not contain an assets array.")

    by_name: dict[str, list[tuple[str, int]]] = {}
    for asset in raw_assets:
        if not isinstance(asset, dict):
            continue
        name = asset.get("name")
        url = asset.get("browser_download_url")
        if isinstance(name, str) and isinstance(url, str):
            raw_size = asset.get("size")
            size = raw_size if isinstance(raw_size, int) and raw_size >= 0 else 0
            by_name.setdefault(name, []).append((url, size))

    download_jobs: list[Job] = []
    for target, selected_engine in pending_pairs:
        asset_name = expected_asset_name(target, selected_engine, selected_version)
        matches = by_name.get(asset_name, [])
        if len(matches) != 1:
            raise RuntimeError(
                f"Expected one release asset named {asset_name}; found {len(matches)}."
            )
        url, size = matches[0]
        download_jobs.append(Job(target, selected_engine, asset_name, url, size))

    if not console.interactive:
        console.info("Download", f"{len(download_jobs)} files.")
    downloaded: list[tuple[Job, Path, int]] = []
    failures: list[str] = []
    progress = _DownloadProgress(console, download_jobs)
    worker_count = len(download_jobs) if jobs is None else min(jobs, len(download_jobs))
    try:
        with ThreadPoolExecutor(max_workers=worker_count) as pool:
            futures = {
                pool.submit(_download, job, proxy, progress.advance): job
                for job in download_jobs
            }
            for future in as_completed(futures):
                job = futures[future]
                try:
                    downloaded.append(future.result())
                    progress.complete_file()
                except Exception as error:
                    failures.append(f"{job.asset_name}: {error}")
    finally:
        progress.close()

    if failures:
        for failure in failures:
            console.error("Download", failure)
        for _, archive, _ in downloaded:
            shutil.rmtree(archive.parent, ignore_errors=True)
        return 1

    console.ok("Download", f"{len(downloaded)}/{len(download_jobs)} files.")
    installed = existing
    install_failures: list[str] = []
    try:
        for job, archive, _ in downloaded:
            try:
                _install(job, archive, tag)
                installed += 1
            except Exception as error:
                install_failures.append(f"{job.engine}/{job.target.id}: {error}")
    finally:
        for _, archive, _ in downloaded:
            shutil.rmtree(archive.parent, ignore_errors=True)

    if install_failures:
        for failure in install_failures:
            console.error("Install", failure)
        return 1
    console.ok("Install", f"{installed}/{len(pairs)} under build/platforms/{tag}.")
    return 0
