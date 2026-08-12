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

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration

@OptIn(ExperimentalCompilerApi::class)
class WasmlineCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID
    override val supportsK2 get() = true
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(
            CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY,
            MessageCollector.NONE,
        )
        val transport = configuration.get(GUEST_TRANSPORT_OPTION) ?: if (
            configuration.get(ENABLE_COMPILER_PLUGIN_OPTION, true)
        ) {
            WasmlineGuestTransport.CORE
        } else {
            WasmlineGuestTransport.NONE
        }
        if (transport == WasmlineGuestTransport.NONE) {
            messageCollector.report(CompilerMessageSeverity.INFO, "[Wasmline] compiler plugin disabled")
            return
        }
        val legacyWasiInitExport = configuration.get(ENABLE_WASI_INIT_EXPORT_OPTION, false)
        IrGenerationExtension.registerExtension(
            WasmlineIrGenerationExtension(
                messageCollector = messageCollector,
                guestTransport = transport,
                enableCoreWasiExports = transport == WasmlineGuestTransport.CORE || legacyWasiInitExport,
            ),
        )
        messageCollector.report(CompilerMessageSeverity.INFO, "[Wasmline] compiler plugin registered for $transport")
    }
}
