/*
 * Copyright (C) 2020 Brian Norman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package crow.wasmline.kotlin

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal const val ENABLE_WASI_INIT_EXPORT_OPTION_NAME = "enableWasiInitExport"
internal const val ENABLE_COMPILER_PLUGIN_OPTION_NAME = "enabled"
internal const val GUEST_TRANSPORT_OPTION_NAME = "guestTransport"
internal enum class WasmlineGuestTransport {
    CORE,
    COMPONENT_SERVICE,
    NONE,
}

internal val GUEST_TRANSPORT_OPTION = CompilerConfigurationKey<WasmlineGuestTransport>(
    "Wasmline guest transport",
)
internal val ENABLE_COMPILER_PLUGIN_OPTION = CompilerConfigurationKey<Boolean>(
    "enable Wasmline IR generation",
)
internal val ENABLE_WASI_INIT_EXPORT_OPTION = CompilerConfigurationKey<Boolean>(
    "enable generated wasm entry export for wasmWasi compilations",
)

@OptIn(ExperimentalCompilerApi::class)
public class WasmlineCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID
    override val pluginOptions: Collection<CliOption> = listOf(
        CliOption(
            optionName = GUEST_TRANSPORT_OPTION_NAME,
            valueDescription = "CORE|COMPONENT_SERVICE|NONE",
            description = "Select the statically linked Wasmline guest transport.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = ENABLE_COMPILER_PLUGIN_OPTION_NAME,
            valueDescription = "true|false",
            description = "Enable Wasmline IR generation for this compilation.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = ENABLE_WASI_INIT_EXPORT_OPTION_NAME,
            valueDescription = "true|false",
            description = "Generate a wasmWasi entry export in the final module.",
            required = false,
            allowMultipleOccurrences = false,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            GUEST_TRANSPORT_OPTION_NAME -> {
                configuration.put(
                    GUEST_TRANSPORT_OPTION,
                    WasmlineGuestTransport.entries.firstOrNull { it.name == value.uppercase() }
                        ?: error("Unknown Wasmline guest transport: $value"),
                )
            }

            ENABLE_COMPILER_PLUGIN_OPTION_NAME -> {
                configuration.put(ENABLE_COMPILER_PLUGIN_OPTION, value.toBooleanStrictOrNull() ?: true)
            }

            ENABLE_WASI_INIT_EXPORT_OPTION_NAME -> {
                configuration.put(ENABLE_WASI_INIT_EXPORT_OPTION, value.toBooleanStrictOrNull() ?: false)
            }

            else -> error("Unknown Wasmline compiler option: ${option.optionName}")
        }
    }
}
