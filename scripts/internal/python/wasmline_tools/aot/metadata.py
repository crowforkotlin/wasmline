"""Resolve immutable compiler metadata for a new Wasmtime AOT distribution."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import tarfile
import tempfile
import zipfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import ProxyHandler, Request, build_opener


WASMTIME_REPOSITORY = "crowforkotlin/wasmtime"
WASMTIME_API_ROOT = f"https://api.github.com/repos/{WASMTIME_REPOSITORY}"
AOT_COMPILER_BUILD_HOSTS = (
    "aarch64-linux",
    "aarch64-macos",
    "x86_64-linux",
    "x86_64-macos",
    "x86_64-windows",
)
_DISTRIBUTION_VERSION_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+\.[1-9][0-9]*$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_SOURCE_REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")
_MAX_EXECUTABLE_SIZE = 512 * 1024 * 1024


class AotMetadataResolutionError(RuntimeError):
    """Raised when a fork release cannot provide complete verified AOT metadata."""


@dataclass(frozen=True)
class ResolvedAotDistribution:
    """Contains one immutable Wasmtime source revision and its compiler assets."""

    source_revision: str
    compiler_assets: tuple[dict[str, Any], ...]


class AotMetadataResolver(Protocol):
    """Provides verified detailed metadata for one fork distribution."""

    def resolve(self, distribution_version: str) -> ResolvedAotDistribution:
        """Resolve one exact fork distribution."""

        ...


@dataclass(frozen=True)
class _AssetRequest:
    """Defines one exact full Wasmtime CLI archive required by AOT builds."""

    build_host: str
    archive_format: str
    asset_id: str
    archive_name: str
    download_url: str
    archive_sha256: str
    archive_size: int
    executable_relative_path: str


class GitHubAotMetadataResolver:
    """Resolves and verifies a fork release without retaining downloaded archives."""

    def __init__(
        self,
        *,
        proxy: str | None = None,
        jobs: int | None = None,
        token: str | None = None,
        timeout_seconds: float = 120.0,
    ) -> None:
        if jobs is not None and jobs < 1:
            raise AotMetadataResolutionError("AOT metadata jobs must be greater than zero.")
        self._proxy = proxy
        self._jobs = min(jobs or len(AOT_COMPILER_BUILD_HOSTS), len(AOT_COMPILER_BUILD_HOSTS))
        self._token = token or os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
        self._timeout_seconds = timeout_seconds

    def resolve(self, distribution_version: str) -> ResolvedAotDistribution:
        """Resolve profiles' source revision and every supported compiler host asset."""

        if not _DISTRIBUTION_VERSION_PATTERN.fullmatch(distribution_version):
            raise AotMetadataResolutionError(
                f"Invalid Wasmtime fork distribution version: {distribution_version!r}."
            )
        tag = f"v{distribution_version}"
        release = self._request_json(
            f"{WASMTIME_API_ROOT}/releases/tags/{quote(tag, safe='')}"
        )
        if release.get("tag_name") != tag or release.get("draft") is not False:
            raise AotMetadataResolutionError(
                f"GitHub did not return the stable fork release {tag}."
            )
        if release.get("prerelease") is not False:
            raise AotMetadataResolutionError(
                f"Wasmtime fork release {tag} is marked as a prerelease."
            )

        commit = self._request_json(
            f"{WASMTIME_API_ROOT}/commits/{quote(tag, safe='')}"
        )
        source_revision = commit.get("sha")
        if not isinstance(source_revision, str) or not _SOURCE_REVISION_PATTERN.fullmatch(source_revision):
            raise AotMetadataResolutionError(
                f"Wasmtime fork release {tag} does not resolve to one source revision."
            )

        assets = release.get("assets")
        if not isinstance(assets, list):
            raise AotMetadataResolutionError(f"Wasmtime fork release {tag} has no asset list.")
        assets_by_name: dict[str, list[dict[str, Any]]] = {}
        for asset in assets:
            if isinstance(asset, dict) and isinstance(asset.get("name"), str):
                assets_by_name.setdefault(str(asset["name"]), []).append(asset)

        checksum_asset = self._single_asset(assets_by_name, "SHA256SUMS", tag)
        checksum_url = self._required_https_url(checksum_asset, "SHA256SUMS")
        try:
            checksum_text = self._request_bytes(checksum_url).decode("utf-8")
        except UnicodeDecodeError as error:
            raise AotMetadataResolutionError(
                f"Wasmtime fork release {tag} has a non-UTF-8 SHA256SUMS asset."
            ) from error
        checksums = self._parse_checksums(checksum_text)
        requests = tuple(
            self._asset_request(distribution_version, build_host, assets_by_name, checksums)
            for build_host in AOT_COMPILER_BUILD_HOSTS
        )
        with ThreadPoolExecutor(max_workers=self._jobs) as executor:
            resolved = tuple(executor.map(self._resolve_asset, requests))
        return ResolvedAotDistribution(
            source_revision=source_revision,
            compiler_assets=tuple(sorted(resolved, key=lambda item: str(item["archiveSha256"]))),
        )

    def _asset_request(
        self,
        distribution_version: str,
        build_host: str,
        assets_by_name: dict[str, list[dict[str, Any]]],
        checksums: dict[str, str],
    ) -> _AssetRequest:
        extension = "zip" if build_host.endswith("windows") else "tar.gz"
        archive_format = "ZIP" if extension == "zip" else "TAR_GZ"
        asset_id = f"wasmtime-v{distribution_version}-{build_host}"
        archive_name = f"{asset_id}.{extension}"
        asset = self._single_asset(assets_by_name, archive_name, f"v{distribution_version}")
        archive_size = asset.get("size")
        if isinstance(archive_size, bool) or not isinstance(archive_size, int) or archive_size <= 0:
            raise AotMetadataResolutionError(
                f"Wasmtime compiler asset {archive_name} has an invalid size."
            )
        archive_sha256 = checksums.get(archive_name)
        if archive_sha256 is None:
            raise AotMetadataResolutionError(
                f"SHA256SUMS does not contain Wasmtime compiler asset {archive_name}."
            )
        executable_name = "wasmtime.exe" if build_host.endswith("windows") else "wasmtime"
        return _AssetRequest(
            build_host=build_host,
            archive_format=archive_format,
            asset_id=asset_id,
            archive_name=archive_name,
            download_url=self._required_https_url(asset, archive_name),
            archive_sha256=archive_sha256,
            archive_size=archive_size,
            executable_relative_path=f"{asset_id}/{executable_name}",
        )

    def _resolve_asset(self, request: _AssetRequest) -> dict[str, Any]:
        with tempfile.TemporaryDirectory(prefix="wasmline-aot-metadata-") as directory:
            archive = Path(directory) / request.archive_name
            digest = hashlib.sha256()
            downloaded = 0
            try:
                with self._open(request.download_url) as response, archive.open("wb") as output:
                    while chunk := response.read(1024 * 1024):
                        downloaded += len(chunk)
                        if downloaded > request.archive_size:
                            raise AotMetadataResolutionError(
                                f"Wasmtime compiler asset {request.archive_name} exceeds its release size."
                            )
                        digest.update(chunk)
                        output.write(chunk)
            except (HTTPError, URLError, OSError, ValueError) as error:
                raise AotMetadataResolutionError(
                    f"Cannot download Wasmtime compiler asset {request.archive_name}: {error}."
                ) from error
            if downloaded != request.archive_size:
                raise AotMetadataResolutionError(
                    f"Wasmtime compiler asset {request.archive_name} has size {downloaded}; "
                    f"expected {request.archive_size}."
                )
            if digest.hexdigest() != request.archive_sha256:
                raise AotMetadataResolutionError(
                    f"Wasmtime compiler asset {request.archive_name} failed SHA-256 verification."
                )
            try:
                executable_sha256 = self._executable_sha256(archive, request)
            except (tarfile.TarError, zipfile.BadZipFile, OSError) as error:
                raise AotMetadataResolutionError(
                    f"Wasmtime compiler asset {request.archive_name} is not a valid {request.archive_format} archive."
                ) from error
        return {
            "buildHost": request.build_host,
            "distribution": "FULL",
            "archiveFormat": request.archive_format,
            "assetId": request.asset_id,
            "archiveName": request.archive_name,
            "downloadUrls": [request.download_url],
            "archiveSha256": request.archive_sha256,
            "archiveSize": request.archive_size,
            "executableRelativePath": request.executable_relative_path,
            "executableSha256": executable_sha256,
        }

    def _executable_sha256(self, archive: Path, request: _AssetRequest) -> str:
        if request.archive_format == "ZIP":
            with zipfile.ZipFile(archive) as source:
                matches = [item for item in source.infolist() if item.filename == request.executable_relative_path]
                if len(matches) != 1:
                    raise AotMetadataResolutionError(
                        f"Wasmtime compiler asset {request.archive_name} does not contain one expected executable."
                    )
                member = matches[0]
                if member.is_dir() or stat.S_ISLNK(member.external_attr >> 16):
                    raise AotMetadataResolutionError(
                        f"Wasmtime compiler asset {request.archive_name} has an invalid executable entry."
                    )
                if member.file_size <= 0 or member.file_size > _MAX_EXECUTABLE_SIZE:
                    raise AotMetadataResolutionError(
                        f"Wasmtime compiler asset {request.archive_name} has an invalid executable size."
                    )
                with source.open(member) as executable:
                    return self._stream_sha256(executable)

        with tarfile.open(archive, mode="r:gz") as source:
            matches = [item for item in source.getmembers() if item.name == request.executable_relative_path]
            if len(matches) != 1 or not matches[0].isfile():
                raise AotMetadataResolutionError(
                    f"Wasmtime compiler asset {request.archive_name} does not contain one expected executable."
                )
            member = matches[0]
            if member.size <= 0 or member.size > _MAX_EXECUTABLE_SIZE:
                raise AotMetadataResolutionError(
                    f"Wasmtime compiler asset {request.archive_name} has an invalid executable size."
                )
            executable = source.extractfile(member)
            if executable is None:
                raise AotMetadataResolutionError(
                    f"Cannot read the executable in Wasmtime compiler asset {request.archive_name}."
                )
            with executable:
                return self._stream_sha256(executable)

    def _request_json(self, url: str) -> dict[str, Any]:
        try:
            value = json.loads(self._request_bytes(url).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AotMetadataResolutionError(f"GitHub returned invalid JSON for {url}: {error}.") from error
        if not isinstance(value, dict):
            raise AotMetadataResolutionError(f"GitHub returned an invalid object for {url}.")
        return value

    def _request_bytes(self, url: str) -> bytes:
        try:
            with self._open(url) as response:
                return response.read()
        except (HTTPError, URLError, OSError, ValueError) as error:
            raise AotMetadataResolutionError(f"Cannot read {url}: {error}.") from error

    def _open(self, url: str):
        parsed_url = urlsplit(url)
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "wasmline-aot-metadata",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self._token and parsed_url.scheme == "https" and parsed_url.hostname == "api.github.com":
            headers["Authorization"] = f"Bearer {self._token}"
        proxy = self._proxy if not self._proxy or "://" in self._proxy else f"http://{self._proxy}"
        opener = build_opener(ProxyHandler({"http": proxy, "https": proxy})) if proxy else build_opener()
        return opener.open(Request(url, headers=headers), timeout=self._timeout_seconds)

    @staticmethod
    def _single_asset(
        assets_by_name: dict[str, list[dict[str, Any]]],
        name: str,
        release: str,
    ) -> dict[str, Any]:
        matches = assets_by_name.get(name, [])
        if len(matches) != 1:
            raise AotMetadataResolutionError(
                f"Wasmtime fork release {release} must contain one asset named {name}; found {len(matches)}."
            )
        return matches[0]

    @staticmethod
    def _required_https_url(asset: dict[str, Any], name: str) -> str:
        value = asset.get("browser_download_url")
        try:
            parsed = urlsplit(value) if isinstance(value, str) else None
        except ValueError as error:
            raise AotMetadataResolutionError(
                f"Wasmtime release asset {name} has an invalid download URL."
            ) from error
        if (
            parsed is None
            or parsed.scheme != "https"
            or not parsed.hostname
            or parsed.username is not None
            or parsed.password is not None
            or any(char.isspace() for char in value)
        ):
            raise AotMetadataResolutionError(f"Wasmtime release asset {name} has an invalid download URL.")
        return value

    @staticmethod
    def _parse_checksums(value: str) -> dict[str, str]:
        checksums: dict[str, str] = {}
        for line in value.splitlines():
            fields = line.split(maxsplit=1)
            if len(fields) != 2:
                raise AotMetadataResolutionError("Wasmtime SHA256SUMS contains an invalid line.")
            digest, name = fields
            name = name.lstrip("*")
            digest = digest.lower()
            if not _SHA256_PATTERN.fullmatch(digest) or not name or name in checksums:
                raise AotMetadataResolutionError(
                    f"Wasmtime SHA256SUMS contains an invalid entry for {name!r}."
                )
            checksums[name] = digest
        if not checksums:
            raise AotMetadataResolutionError("Wasmtime SHA256SUMS is empty.")
        return checksums

    @staticmethod
    def _stream_sha256(source: Any) -> str:
        digest = hashlib.sha256()
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
        return digest.hexdigest()
