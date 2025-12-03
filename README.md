# WasmLine
- Support (Windows、MacOS、Android)

# PreBuild
- Init platforms library

`sh ./scripts/init.sh`

# Samples
`sh ./scripts/samples/run.sh`
<table>
	<tr>
		<td align="center"><img src="docs/images/android_sample.png"></td>
		<td align="center"><img src="docs/images/macos_sample.png"></td>
	</tr>
    <tr>
		<td align="center">android</td>
		<td align="center">macos</td>
	</tr>
</table>

# compile cwasm (AOT Mode)
```
wasmtime compile plugin.wasm -o plugin.cwasm \
    --target aarch64-linux-android \
    -W gc=y \
    -W function-references=y \
    -W exceptions=y \
    -W simd=n \
    -W relaxed-simd=n \
    -O static-memory-guard-size=0 \
    -O dynamic-memory-guard-size=0 \
    -O signals-based-traps=n \
    -O opt-level=2 \
    -C cranelift-debug-verifier=no
```