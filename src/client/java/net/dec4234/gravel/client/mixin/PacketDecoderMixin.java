package net.dec4234.gravel.client.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.dec4234.gravel.client.bridge.PacketLog;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Puts each packet the client receives from the server in the packet log, with its size. */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {
	@Shadow
	@Final
	private ProtocolInfo<?> protocolInfo;

	/** The size of the packet that decode reads now. Each channel has its own decoder and one thread. */
	@Unique
	private int gravel$packetBytes;

	@Inject(method = "decode", at = @At("HEAD"))
	private void gravel$measurePacket(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
		gravel$packetBytes = in.readableBytes();
	}

	@Inject(method = "decode", at = @At("RETURN"))
	private void gravel$logPacket(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
		// Only the client decodes clientbound packets. A LAN server in this game decodes serverbound packets.
		if (protocolInfo.flow() == PacketFlow.CLIENTBOUND && !out.isEmpty() && out.getLast() instanceof Packet<?> packet) {
			PacketLog.record(PacketFlow.CLIENTBOUND, protocolInfo.id(), packet, gravel$packetBytes);
		}
	}
}
