wit_bindgen::generate!({
    world: "plugin",
});

struct RustPlugin;

impl exports::plugin::Guest for RustPlugin {
    fn invoke(
        request: exports::plugin::Request,
    ) -> Result<Vec<u8>, exports::plugin::RpcError> {
        let exports::plugin::Request {
            action,
            codec,
            payload,
        } = request;

        if codec != SUPPORTED_CODEC {
            return Err(plugin_error(
                "1005",
                "Unsupported codec. This fixture accepts the protobuf envelope and forwards payload bytes unchanged.",
                Vec::new(),
            ));
        }

        match action.as_str() {
            ACTION_ECHO => Ok(payload),
            ACTION_CALLBACK => callback_host(codec, payload),
            ACTION_EMPTY => Ok(Vec::new()),
            ACTION_TRAP => panic!("Intentional Component trap from the Rust fixture."),
            _ => Err(plugin_error(
                "1002",
                format!("Unknown Rust Component action: {action}."),
                Vec::new(),
            )),
        }
    }
}

fn callback_host(
    codec: String,
    payload: Vec<u8>,
) -> Result<Vec<u8>, exports::plugin::RpcError> {
    host::invoke(&host::Request {
        action: HOST_CALLBACK_ACTION.to_owned(),
        codec,
        payload,
    })
    .map_err(|error| exports::plugin::RpcError {
        code: error.code,
        message: error.message,
        details: error.details,
    })
}

fn plugin_error(
    code: &str,
    message: impl Into<String>,
    details: Vec<u8>,
) -> exports::plugin::RpcError {
    exports::plugin::RpcError {
        code: code.to_owned(),
        message: message.into(),
        details,
    }
}

const SUPPORTED_CODEC: &str = "protobuf";
const ACTION_ECHO: &str = "sample.echo";
const ACTION_CALLBACK: &str = "sample.callback";
const ACTION_EMPTY: &str = "sample.empty";
const ACTION_TRAP: &str = "sample.trap";
const HOST_CALLBACK_ACTION: &str = "sample.host.callback";

export!(RustPlugin);
