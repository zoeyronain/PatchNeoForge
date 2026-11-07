use crate::{
    java::native_callbacks::CALLBACK_CONTEXT,
    proto::patchneoforge::{common::EmptyRequest, config::GetPatchNeoForgeConfigResponse},
};

pub fn ffi_native_bridge_get_patch_neo_forge_config_impl(
    _request: EmptyRequest,
) -> Option<GetPatchNeoForgeConfigResponse> {
    CALLBACK_CONTEXT
        .get()
        .map(|context| GetPatchNeoForgeConfigResponse {
            minimum_supported_plugin_api: "21.1.0".to_string(),
            disabled_plugins: context.config.mods.disabled.clone(),
            download_dependencies: true,
            warn_unimplemented_api: false,
            debug_bridge: context.config.diagnostics.debug_bridge,
            log_level: "INFO".to_string(),
        })
}
