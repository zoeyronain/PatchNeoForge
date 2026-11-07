pub mod patchneoforge {
    pub mod bridge {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.bridge.rs"));
    }

    pub mod common {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.common.rs"));
    }

    pub mod events {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.events.rs"));
    }

    pub mod abilities {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.abilities.rs"));
    }

    pub mod message {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.message.rs"));
    }

    pub mod registry {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.registry.rs"));
    }

    pub mod sound {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.sound.rs"));
    }

    pub mod log {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.log.rs"));
    }

    pub mod config {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.config.rs"));
    }

    pub mod itemstack {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.itemstack.rs"));
    }

    pub mod entity {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.entity.rs"));
    }

    pub mod world {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.world.rs"));
    }

    pub mod command {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.command.rs"));
    }

    pub mod server {
        include!(concat!(env!("OUT_DIR"), "/patchneoforge.server.rs"));
    }
}

include!(concat!(env!("OUT_DIR"), "/ffi_init.rs"));
