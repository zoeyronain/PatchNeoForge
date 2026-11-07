package net.neoforged.neoforge.event.tick;

import net.neoforged.bus.api.Event;

public class ServerTickEvent extends Event {

    public static class Pre extends ServerTickEvent {}

    public static class Post extends ServerTickEvent {}
}
