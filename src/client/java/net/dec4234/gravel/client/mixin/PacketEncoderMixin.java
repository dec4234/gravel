package net.dec4234.gravel.client.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.dec4234.gravel.client.bridge.PacketLog;
import net.minecraft.network.PacketEncoder;
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

/** Puts each packet the client sends to the server in the packet log, with its size. */
@Mixin(PacketEncoder.class)
public abstract class PacketEncoderMixin {
	@Unique
	private static final String ENCODE = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V";

	@Shadow
	@Final
	private ProtocolInfo<?> protocolInfo;

	@Unique
	private int gravel$startIndex;

	@Inject(method = ENCODE, at = @At("HEAD"))
	private void gravel$markStart(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
		gravel$startIndex = out.writerIndex();
	}

	@Inject(method = ENCODE, at = @At("RETURN"))
	private void gravel$logPacket(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
		// Only the client encodes serverbound packets. A LAN server in this game encodes clientbound packets.
		if (protocolInfo.flow() == PacketFlow.SERVERBOUND) {
			PacketLog.record(PacketFlow.SERVERBOUND, protocolInfo.id(), packet, out.writerIndex() - gravel$startIndex);
		}
	}
}
