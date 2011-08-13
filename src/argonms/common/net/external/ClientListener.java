/*
 * ArgonMS MapleStory server emulator written in Java
 * Copyright (C) 2011  GoldenKevin
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package argonms.common.net.external;

import argonms.common.GlobalConstants;
import argonms.common.util.input.LittleEndianByteArrayReader;
import argonms.common.util.output.LittleEndianByteArrayWriter;
import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelException;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelLocal;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder;
import org.jboss.netty.handler.timeout.IdleStateAwareChannelUpstreamHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;

/**
 *
 * @author GoldenKevin
 */
public class ClientListener<T extends RemoteClient> {
	public interface ClientFactory<T extends RemoteClient> {
		public T newInstance(byte world, byte channel);
	}

	private static final Logger LOG = Logger.getLogger(ClientListener.class.getName());
	private ClientFactory<T> clientCtor;
	private ServerBootstrap bootstrap;
	private byte world, channel;
	private ClientPacketProcessor<T> pp;

	public ClientListener(byte world, byte channel, boolean useNio, ClientPacketProcessor<T> packetProcessor, ClientFactory<T> clientFactory) {
		this.clientCtor = clientFactory;
		this.world = world;
		this.channel = channel;
		this.pp = packetProcessor;
		ChannelFactory chFactory;
		if (useNio) {
			chFactory = new NioServerSocketChannelFactory(
				Executors.newSingleThreadExecutor(), //we don't need more than one boss thread
				Executors.newCachedThreadPool()
			);
		} else {
			chFactory = new OioServerSocketChannelFactory(
				Executors.newSingleThreadExecutor(), //we don't need more than one boss thread
				Executors.newFixedThreadPool(1200) //max players
			);
		}
		bootstrap = new ServerBootstrap(chFactory);
		bootstrap.setOption("child.tcpNoDelay", true);
		bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
			@Override
			public ChannelPipeline getPipeline() throws Exception {
				ChannelPipeline pipeline = Channels.pipeline();
				pipeline.addLast("decoder", new MaplePacketDecoder());
				pipeline.addLast("encoder", new MaplePacketEncoder());
				pipeline.addLast("timeout", new IdleStateHandler(new HashedWheelTimer(), 0, 0, 30));
				pipeline.addLast("handler", new MapleServerHandler());
				return pipeline;
			}
		});
	}

	public boolean bind(int port) {
		try {
			bootstrap.bind(new InetSocketAddress(port));
			LOG.log(Level.INFO, "Listening on port {0}", port);
			return true;
		} catch (ChannelException ex) {
			LOG.log(Level.SEVERE, "Could not bind on port " + port, ex);
			return false;
		}
	}

	private final ChannelLocal<ClientSession<T>> sessions = new ChannelLocal<ClientSession<T>>();

	private final class MaplePacketDecoder extends FrameDecoder {
		@Override
		protected Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer buf) throws Exception {
			if (buf.readableBytes() < 4)
				return null;

			buf.markReaderIndex();
			byte[] message = new byte[4];
			buf.readBytes(message);

			ClientSession<T> session = sessions.get(channel);
			boolean recvIvUnlocked = false;
			session.lockRecv();
			try {
				byte[] iv = session.getRecvIv();
				if (MapleAesOfb.checkPacket(message, iv)) {
					int length = MapleAesOfb.getPacketLength(message);

					if (buf.readableBytes() < length) {
						buf.resetReaderIndex();
						return null;
					}
					session.setRecvIv(MapleAesOfb.nextIv(iv));
					session.unlockRecv();
					recvIvUnlocked = true;

					message = new byte[length];
					buf.readBytes(message);

					MapleAesOfb.aesCrypt(message, iv);
					return MapleAesOfb.mapleDecrypt(message);
				} else {
					LOG.log(Level.FINE, "Client from {0} failed packet check -> Disconnecting.", channel.getRemoteAddress());
					sessions.get(channel).close();
					return null;
				}
			} finally {
				if (!recvIvUnlocked)
					session.unlockRecv();
			}
		}
	}

	private final class MaplePacketEncoder extends OneToOneEncoder {
		@Override
		protected Object encode(ChannelHandlerContext ctx, Channel channel, Object msg) throws Exception {
			//we mutexed ClientSession.send as a temporary solution to the
			//packet dropping problem, but that is highly inefficient as it also
			//mutexes the code in the Netty pipeline and it doesn't allow
			//array copying, Maple custom encoding, and AES encoding to be done
			//in parallel. As long as we have unique IVs per send and messages
			//are sent in the order that their IVs are updated, we should get
			//away with performing these expensive operations in parallel. find
			//a way to queue messages to send such that messages are sent in the
			//order that they updated the send IV.
			ClientSession<T> session = sessions.get(channel);
			if (session != null) {
				byte[] input = (byte[]) msg;
				int length = input.length;
				byte[] body = new byte[length];
				System.arraycopy(input, 0, body, 0, length);
				MapleAesOfb.mapleEncrypt(body);

				//I'm not sure whether it's because a set of parallel calls to
				//ChannelBuffers.copiedBuffer finishes in a different order than
				//when write was called and that the mixed up order messes up
				//the IV stuff, or whether it's just that the client can't
				//handle the send rate and that synchronizing this whole area
				//gives enough delay on the throughput, but if we only consider
				//getSendIv() and setSendIv() as critical code and allow the
				//rest to execute in parallel with other send message tasks, the
				//client starts dropping packets when there's a lot of
				//concurrent sending.
				byte[] iv;
				//session.lockSend();
				//try {
					iv = session.getSendIv();
					session.setSendIv(MapleAesOfb.nextIv(iv));
				//} finally {
					//session.unlockSend();
				//}
				byte[] header = MapleAesOfb.getPacketHeader(length, iv);
				MapleAesOfb.aesCrypt(body, iv);
				return ChannelBuffers.copiedBuffer(header, body);
			} else {
				byte[] input = (byte[]) msg;
				ChannelBuffer buf = ChannelBuffers.buffer(ByteOrder.LITTLE_ENDIAN, input.length + 2);
				buf.writeShort(input.length);
				buf.writeBytes(input);
				return buf;
			}
		}
	}

	private class MapleServerHandler extends IdleStateAwareChannelUpstreamHandler {
		@Override
		public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
			LOG.log(Level.FINEST, "Trying to accept client from {0}", e.getChannel().getRemoteAddress());
		}

		@Override
		public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
			LOG.log(Level.FINE, "Client connected from {0}", e.getChannel().getRemoteAddress());
			T client = clientCtor.newInstance(world, channel);
			ClientSession<T> session = new ClientSession<T>(e.getChannel(), client);
			client.setSession(session);
			e.getChannel().write(getHello(session.getRecvIv(), session.getSendIv()));
			sessions.set(e.getChannel(), session);
		}

		@Override
		public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e) throws Exception {
			LOG.log(Level.FINER, "Client from {0} went idle -> pinging", e.getChannel().getRemoteAddress());
			sessions.get(e.getChannel()).getClient().startPingTask();
		}

		@Override
		public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
			LOG.log(Level.FINE, "Client from {0} disconnected", e.getChannel().getRemoteAddress());
			sessions.get(e.getChannel()).getClient().disconnected();
		}

		@Override
		public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
			LOG.log(Level.FINEST, "Removing client from {0}", e.getChannel().getRemoteAddress());
			sessions.remove(e.getChannel());
		}

		@Override
		public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
			byte[] message = (byte[]) e.getMessage();
			pp.process(new LittleEndianByteArrayReader(message), sessions.get(e.getChannel()).getClient());
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
			LOG.log(Level.FINE, "Exception raised in external network facing code (client " + e.getChannel().getRemoteAddress() + ")", e.getCause());
		}
	}

	private static byte[] getHello(byte[] recvIv, byte[] sendIv) {
		LittleEndianByteArrayWriter lew = new LittleEndianByteArrayWriter(13);

		lew.writeShort(GlobalConstants.MAPLE_VERSION);
		lew.writeShort((short) 0);
		lew.writeBytes(recvIv);
		lew.writeBytes(sendIv);
		lew.writeByte((byte) 8);

		return lew.getBytes();
	}
}