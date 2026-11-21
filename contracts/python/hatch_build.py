"""Hatch build hook: compiles ../proto into Python modules for the wheel."""

from pathlib import Path
from typing import Any

from hatchling.builders.hooks.plugin.interface import BuildHookInterface

PROTO_ROOT = Path(__file__).resolve().parent.parent / "proto"


class ProtoBuildHook(BuildHookInterface):
    def initialize(self, version: str, build_data: dict[str, Any]) -> None:
        from grpc_tools import protoc

        out_dir = Path(self.directory) / "gen"
        out_dir.mkdir(parents=True, exist_ok=True)
        protos = sorted(str(p) for p in PROTO_ROOT.rglob("*.proto"))
        if not protos:
            raise RuntimeError(f"no .proto files under {PROTO_ROOT}")

        well_known = Path(protoc.__file__).parent / "_proto"
        exit_code = protoc.main(
            [
                "protoc",
                f"-I{PROTO_ROOT}",
                f"-I{well_known}",
                f"--python_out={out_dir}",
                f"--pyi_out={out_dir}",
                *protos,
            ]
        )
        if exit_code != 0:
            raise RuntimeError(f"protoc failed with exit code {exit_code}")

        for generated in out_dir.rglob("*.py*"):
            build_data["force_include"][str(generated)] = str(generated.relative_to(out_dir))
