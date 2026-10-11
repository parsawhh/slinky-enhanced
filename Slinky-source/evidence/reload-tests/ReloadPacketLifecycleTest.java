import dev.slinky.client.ClientEventBridge;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

public class ReloadPacketLifecycleTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        LocalEventLoopGroup loop = new LocalEventLoopGroup(1);
        LocalChannel channel = new LocalChannel();
        loop.register(channel).sync();
        channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
        channel.pipeline().addBefore("packet_handler", "slinky_semantic_packets", new ChannelInboundHandlerAdapter());
        ClientEventBridge oldBridge = new ClientEventBridge(null, null, null);
        Field attached = ClientEventBridge.class.getDeclaredField("attachedChannel");
        attached.setAccessible(true);
        attached.set(oldBridge, channel);
        ClientEventBridge.install(oldBridge);
        ClientEventBridge.install(null);
        require(channel.pipeline().get("slinky_semantic_packets") == null, "unload must remove packet handler");
        require(attached.get(oldBridge) == null, "unload must release channel reference");

        Constructor<?> queuedAttach = Class.forName("dev.slinky.client.ClientEventBridge$2").getDeclaredConstructor(ClientEventBridge.class, Channel.class);
        queuedAttach.setAccessible(true);
        Runnable staleAttach = (Runnable)queuedAttach.newInstance(oldBridge, channel);
        staleAttach.run();
        require(channel.pipeline().get("slinky_semantic_packets") == null, "late attach must not restore an unloaded handler");

        ClientEventBridge newBridge = new ClientEventBridge(null, null, null);
        attached.set(newBridge, channel);
        ClientEventBridge.install(newBridge);
        staleAttach.run();
        require(channel.pipeline().get("slinky_semantic_packets") == null, "old callback must not attach to replacement session");
        Runnable freshAttach = (Runnable)queuedAttach.newInstance(newBridge, channel);
        freshAttach.run();
        require(channel.pipeline().get("slinky_semantic_packets") != null, "active session must attach its handler");
        ClientEventBridge.install(null);
        require(channel.pipeline().get("slinky_semantic_packets") == null, "new session unload must detach handler");
        channel.close().sync();
        loop.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.MILLISECONDS).sync();
        System.out.println("PASS: unload detaches Netty handler; stale queued callbacks cannot reattach; new session attaches and detaches.");
    }
}
