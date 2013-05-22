/* PGM socket: manage incoming & outgoing sockets with ambient SPMs,
 * transmit & receive windows.
 */ 

package hk.miru.javapgm;

import static hk.miru.javapgm.Preconditions.checkNotNull;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.MembershipKey;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Queue;
import java.util.Random;
import java.util.logging.Level;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

public class Socket {
        private static Logger LOG = LogManager.getLogger (Socket.class.getName()); 

        ProtocolFamily family = null;
        TransportSessionId tsi = null;
	int dataDestinationPort = 0;
	int udpEncapsulationUnicastPort = 0;
	int udpEncapsulationMulticastPort = 0;
        
        boolean isBound = false;
        boolean isConnected = false;
        boolean isDestroyed = false;
        boolean isReset = false;
	boolean shouldAbortOnReset = false;

        boolean canSendNak = true;
	boolean canReceiveData = true;
        
        public static final int IP_MAX_MEMBERSHIPS = 20;

        hk.miru.javapgm.GroupSourceRequest send_gsr = null;
	InetAddress send_addr = null;
	MulticastSocket send_sock = null;
        LinkedList<hk.miru.javapgm.GroupSourceRequest> recv_gsr = new LinkedList<>();
	DatagramChannel recv_sock = null;
        
        int max_apdu = 0;
	int max_tpdu = 0;
        int max_tsdu = 0;
        int max_tsdu_fragment = 0;
        int iphdr_len = 0;
        int hops = 0;
        int rxw_sqns = 0, rxw_secs = 0;
	long rxw_max_rte = 0;

        long lastCommit = 0;
        
        Random rand = null;
	long nak_data_retries = 0;
	long nak_ncf_retries = 0;
	long nak_bo_ivl = 0;
	long nak_rpt_ivl = 0;
        long nak_rdata_ivl = 0;

	long peerExpiration = 0;
        long spmrExpiration = 0;

	ByteBuffer buffer = null;
	SocketBuffer rx_buffer = null;      
        
	Hashtable<TransportSessionId, Peer> peers_hashtable = null;
	LinkedList<Peer> peers_pending = new LinkedList<>();
	boolean hasPendingRead = false;
	long nextPoll = 0;
/* Workaround Java lack of pass-by-reference for source peer. */        
        Peer[] source = new Peer[1];

	public enum IoStatus {
		IO_STATUS_ERROR,
		IO_STATUS_NORMAL,
		IO_STATUS_RESET,
		IO_STATUS_FIN,
		IO_STATUS_EOF,
		IO_STATUS_WOULD_BLOCK,
		IO_STATUS_RATE_LIMITED,
		IO_STATUS_TIMER_PENDING,
		IO_STATUS_CONGESTION
	}

	public Socket (ProtocolFamily family) throws IOException
	{
                this.family = family;
                this.canSendNak = true;
                this.canReceiveData = true;
                this.dataDestinationPort = Packet.DEFAULT_DATA_DESTINATION_PORT;
                this.tsi = new TransportSessionId (null, Packet.DEFAULT_DATA_SOURCE_PORT);
                
		this.recv_sock = DatagramChannel.open (this.family);
                this.recv_sock.setOption (StandardSocketOptions.SO_REUSEADDR, true);
                this.recv_sock.configureBlocking (false);                
                        
		this.send_sock = new MulticastSocket ();
        }
        
        public boolean bind (hk.miru.javapgm.SocketAddress sockaddr, @Nullable InterfaceRequest send_req, @Nullable InterfaceRequest recv_req)
        {
                checkNotNull (sockaddr);

                if (this.isBound || this.isDestroyed)
                        return false;
                
/* Sanity checks on state */
                if (this.max_tpdu < (Packet.SIZEOF_IP_HEADER + Packet.SIZEOF_PGM_HEADER)) {
                        return false;
                }
                if (this.canReceiveData) {
                        if (0 == this.rxw_sqns && 0 == this.rxw_secs) {
                                return false;
                        }
                        if (0 == this.rxw_sqns && 0 == this.rxw_max_rte) {
                                return false;
                        }
                        if (0 == this.peerExpiration) {
                                return false;
                        }
                        if (0 == this.spmrExpiration) {
                                return false;
                        }
                        if (0 == this.nak_bo_ivl) {
                                return false;
                        }
                        if (0 == this.nak_rpt_ivl) {
                                return false;
                        }
                        if (0 == this.nak_rdata_ivl) {
                                return false;
                        }
                        if (0 == this.nak_data_retries) {
                                return false;
                        }
                        if (0 == this.nak_ncf_retries) {
                                return false;
                        }
                }

                LOG.debug ("bind ()");
                        
                this.tsi = sockaddr.getTransportSessionId();
                this.dataDestinationPort = sockaddr.getPort();
                
/* pseudo-random number generator for back-off intervals */
                this.rand = new Random();

/* Determine IP header size for rate regulation engine & stats */
                if (StandardProtocolFamily.INET == this.family)
                        this.iphdr_len = Packet.SIZEOF_IP_HEADER;
                else
                        this.iphdr_len = Packet.SIZEOF_IP6_HEADER;
                LOG.info ("Assuming IP header size of {} bytes", this.iphdr_len);
                
                ProtocolFamily pgmcc_family = null;
                this.max_tsdu = this.max_tpdu - this.iphdr_len - Packet.calculateOffset (false, pgmcc_family);
                this.max_tsdu_fragment = this.max_tpdu - this.iphdr_len - Packet.calculateOffset (true, pgmcc_family);
                int max_fragments = Packet.PGM_MAX_FRAGMENTS;
                this.max_apdu = Math.min (Packet.PGM_MAX_APDU, max_fragments * this.max_tsdu_fragment);
                
/* Create peer list */
                if (this.canReceiveData) {
                        this.peers_hashtable = new Hashtable<>();
                }
                
/* Bind UDP sockets to interfaces, note multicast on a bound interface is
 * fruity on some platforms.  Roughly,  binding to INADDR_ANY provides all
 * data, binding to the multicast group provides only multicast traffic,
 * and binding to the interface address provides only unicast traffic.
 *
 * Multicast routing, IGMP & MLD require a link local address, for IPv4
 * this is provided through MULTICAST_IF and IPv6 through bind, and these
 * may be overridden by per packet scopes.
 *
 * After binding, default interfaces (0.0.0.0) are resolved.
 */
/* TODO: different ports requires a new bound socket */
                InetSocketAddress recv_addr, send_addr;

                if (StandardProtocolFamily.INET6 == this.family) {
/* TODO: No IPv6 wildcard support */                    
                        recv_addr = new InetSocketAddress ("::", this.udpEncapsulationMulticastPort);
                        send_addr = new InetSocketAddress ("::", 0);
                        LOG.info ("Binding receive socket to IN6ADDR_ANY");
                } else {
                        recv_addr = new InetSocketAddress ("0.0.0.0", this.udpEncapsulationMulticastPort);
                        send_addr = new InetSocketAddress ("0.0.0.0", 0);
                        LOG.info ("Binding receive socket to INADDR_ANY");
                }
                
/* UDP port */                
                try {
                        this.recv_sock.bind (recv_addr);
                        LOG.info ("Bind succeeded on receive address {}", recv_addr);
                } catch (IOException ex) {
                        LOG.error ("Binding receive socket to address {}: {}", recv_addr, ex);
                        return false;
                }

/* Java NetworkInterface is overloaded to provide a tree of interfaces, quite
 * unlike any platform it actually runs upon for IPv4 but valid for IPv6.  When
 * multiple addresses are bound to an interface each should have a unique index
 * and thus a unique NetworkInterface instance.
 */                                
                if (StandardProtocolFamily.INET6 == this.family) {
                        LOG.info ("Binding send socket to interface index {} scope {}", send_req.getNetworkInterfaceIndex(), send_req.getScopeId());
                } else {
                        LOG.info ("Binding send socket to interface index {}", send_req.getNetworkInterfaceIndex());
                }

                try {
                        NetworkInterface ni = NetworkInterface.getByIndex (send_req.getNetworkInterfaceIndex());
                        if (false) {
                                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                                while (inetAddresses.hasMoreElements()) {
                                        InetAddress addr = inetAddresses.nextElement();
                                        if (addr instanceof Inet6Address) {
                                                Inet6Address in6addr = (Inet6Address)addr;
                                                if (in6addr.getScopeId() == send_req.getScopeId()) {
                                                        send_addr = new InetSocketAddress (addr, 0);
                                                        break;
                                                }
                                        } else if (addr instanceof Inet4Address) {
                                                send_addr = new InetSocketAddress (addr, 0);
                                                break;
                                        }
                                }
                                this.send_sock.bind (send_addr);
                        } else {
/* Java does not permit bind on a MulticastSocket */                        
                                this.send_sock.setNetworkInterface (ni);
                        }
                } catch (IOException ex) {
                        LOG.error ("Binding send socket to address {}: {}", send_addr, ex);
                        return false;
                }
                
/* Resolve bound address if wildcard */
                if (send_addr.isUnresolved()) {
                        InetAddress addr;
                        try {
                                addr = hk.miru.javapgm.NetworkInterface.getMulticastEnabledNodeAddress (this.family);
                        } catch (UnknownHostException | SocketException ex) {
                                LOG.error (ex);
                                return false;
                        }
                        send_addr = new InetSocketAddress (addr, send_addr.getPort());
                }
                
                LOG.debug ("Bind succeeded on send_gsr interface {}", send_addr);
                
/* Save send side address for broadcasting as source NLA */
                this.send_addr = send_addr.getAddress();
                
/* Allocate first incoming packet buffer */                
		this.buffer = ByteBuffer.allocateDirect (this.max_tpdu);

/* Bind complete */
                this.isBound = true;
                
                LOG.debug ("PGM socket successfully bound.");
                return true;
        }
        
        public boolean setOption (int optname, Object optval) throws java.net.SocketException, IOException {
                if (this.isConnected || this.isDestroyed)
                        return false;
                
                switch (optname) {
                    
/* IPv4:   68 <= tpdu < 65536           (RFC 2765)
 * IPv6: 1280 <= tpdu < 65536           (RFC 2460)
 */                    
                case SocketOptions.PGM_MTU:
                        if (!(optval instanceof Integer))
                                break;
                        this.max_tpdu = ((Integer)optval).intValue();
                        return true;
                    
/* true = enable multicast loopback.
 * false = default, to disable.
 */                    
                case SocketOptions.PGM_MULTICAST_LOOP:
                        if (!(optval instanceof Boolean))
                                break;
                        this.send_sock.setLoopbackMode (((Boolean)optval).booleanValue());
                        this.recv_sock.setOption (StandardSocketOptions.IP_MULTICAST_LOOP, ((Boolean)optval).booleanValue());
                        return true;
                    
/* 0 < hops < 256, hops == -1 use kernel default (ignored).
 */
                case SocketOptions.PGM_MULTICAST_HOPS:                    
                        if (!(optval instanceof Integer))
                                break;
                        this.hops = ((Integer)optval).intValue();
                        this.send_sock.setTimeToLive (hops);
                        return true;
                    
/* Timeout for peers.
 * 0 < 2 * spm_ambient_interval <= peer_expiry
 */                    
                case SocketOptions.PGM_PEER_EXPIRY:
                        if (!(optval instanceof Integer))
                                break;
                        this.peerExpiration = (((Integer)optval).intValue());
                        return true;

/* Maximum back off range for listening for multicast SPMR.
 * 0 < spmr_expiry < spm_ambient_interval
 */                    
                case SocketOptions.PGM_SPMR_EXPIRY:
                        if (!(optval instanceof Integer))
                                break;
                        this.spmrExpiration = (((Integer)optval).intValue());
                        return true;

/* Size of receive window in sequence numbers.
 * 0 < rxw_sqns < one less than half sequence space
 */                    
                case SocketOptions.PGM_RXW_SQNS:
                        if (!(optval instanceof Integer))
                                break;
                        this.rxw_sqns = (((Integer)optval).intValue());
                        return true;

/* Size of receive window in seconds.
 * 0 < secs < ( rxw_sqns / rxw_max_rte )
 */ 
                case SocketOptions.PGM_RXW_SECS:
                        if (!(optval instanceof Integer))
                                break;
                        this.rxw_secs = (((Integer)optval).intValue());
                        return true;
                    
/* Maximum receive rate, for determining window size with txw_secs.
 * 0 < rxw_max_rte < interface capacity
 */
                case SocketOptions.PGM_RXW_MAX_RTE:
                        if (!(optval instanceof Integer))
                                break;
                        this.rxw_max_rte = (((Integer)optval).intValue());
                        return true;

/* Maximum NAK back-off value nak_rb_ivl in milliseconds.
 * 0 < nak_rb_ivl <= nak_bo_ivl
 */                    
                case SocketOptions.PGM_NAK_BO_IVL:
                        if (!(optval instanceof Integer))
                                break;
                        this.nak_bo_ivl = (((Integer)optval).intValue());
                        return true;

/* Repeat interval prior to re-sending a NAK, in milliseconds.
 */                    
                case SocketOptions.PGM_NAK_RPT_IVL:
                        if (!(optval instanceof Integer))
                                break;
                        this.nak_rpt_ivl = (((Integer)optval).intValue());
                        return true;

/* Interval waiting for repair data, in milliseconds.
 */                    
                case SocketOptions.PGM_NAK_RDATA_IVL:
                        if (!(optval instanceof Integer))
                                break;
                        this.nak_rdata_ivl = (((Integer)optval).intValue());
                        return true;
                    
/* Limit for data.
 * 0 < nak_data_retries < 256
 */                    
                case SocketOptions.PGM_NAK_DATA_RETRIES:
                        if (!(optval instanceof Integer))
                                break;
                        this.nak_data_retries = (((Integer)optval).intValue());
                        return true;
                    
/* Limit for NAK confirms.
 * 0 < nak_ncf_retries < 256
 */                    
                case SocketOptions.PGM_NAK_NCF_RETRIES:
                        if (!(optval instanceof Integer))
                                break;
                        this.nak_ncf_retries = (((Integer)optval).intValue());
                        return true;
                    
/* Passive receiving socket, i.e. no back channel to source
 */                    
                case SocketOptions.PGM_PASSIVE:
                        if (!(optval instanceof Boolean))
                                break;
                        this.canSendNak = !(((Boolean)optval).booleanValue());
                        return true;
                    
/* On unrecoverable data loss stop socket from further transmission and
 * receiving.
 */                    
                case SocketOptions.PGM_ABORT_ON_RESET:
                        if (!(optval instanceof Boolean))
                                break;
                        this.shouldAbortOnReset = !(((Boolean)optval).booleanValue());
                        return true;
                    
/* Sending group, singular.  Note that the address is only stored and used
 * later in sendto() calls, this routine only considers the interface.
 */                    
                case SocketOptions.PGM_SEND_GROUP:
                        if (!(optval instanceof hk.miru.javapgm.GroupRequest))
                                break;
                        {
                                hk.miru.javapgm.GroupRequest gr = (hk.miru.javapgm.GroupRequest)optval;
                                this.send_gsr = new hk.miru.javapgm.GroupSourceRequest (gr.getNetworkInterfaceIndex(), gr.getMulticastAddress(), null);
                                int if_index = gr.getNetworkInterfaceIndex();
                                this.send_sock.setNetworkInterface (NetworkInterface.getByIndex (if_index));
                                LOG.info ("Multicast send interface set to index {}", gr.getNetworkInterfaceIndex());
                        }
                        return true;
                    
/* For any-source applications (ASM), join a new group
 */                    
                case SocketOptions.PGM_JOIN_GROUP:
                        if (!(optval instanceof hk.miru.javapgm.GroupRequest))
                                break;
                        {
                                hk.miru.javapgm.GroupRequest gr = (hk.miru.javapgm.GroupRequest)optval;
                                hk.miru.javapgm.GroupSourceRequest gsr = new hk.miru.javapgm.GroupSourceRequest (gr.getNetworkInterfaceIndex(), gr.getMulticastAddress(), null);
                                this.recv_gsr.add (gsr);
                                MembershipKey key = this.recv_sock.join (gr.getMulticastAddress(), NetworkInterface.getByIndex (gr.getNetworkInterfaceIndex()));
                                LOG.info ("Join multicast group {} on interface index {}", gr.getMulticastAddress(), gr.getNetworkInterfaceIndex());
                        }
                        return true;

/* For any-source applications (ASM), leave a joined group.
 */                    
                case SocketOptions.PGM_LEAVE_GROUP:
                        // key.drop();
                        return false;
                    
/* For any-source applications (ASM), turn off a given source
 */ 
                case SocketOptions.PGM_BLOCK_SOURCE:
                        // key.block (source);
                        return false;
                    
/* For any-source applications (ASM), re-allow a blocked source
 */ 
                case SocketOptions.PGM_UNBLOCK_SOURCE:
                        // key.unblock (source);
                        return false;
                    
/* For controlled-source applications (SSM), join each group/source pair.
 *
 * SSM joins are allowed on top of ASM in order to merge a remote source onto the local segment.
 */                    
                case SocketOptions.PGM_JOIN_SOURCE_GROUP:
                        if (!(optval instanceof hk.miru.javapgm.GroupSourceRequest))
                                break;
                        {
                                hk.miru.javapgm.GroupSourceRequest gsr = (hk.miru.javapgm.GroupSourceRequest)optval;
                                MembershipKey key = this.recv_sock.join (gsr.getMulticastAddress(), NetworkInterface.getByIndex (gsr.getNetworkInterfaceIndex()), gsr.getSourceAddress());
                                LOG.info ("Join multicast group {} on interface index {} for source {}",
                                          gsr.getMulticastAddress(), gsr.getNetworkInterfaceIndex(), gsr.getSourceAddress());
                        }
                        return true;
                    
/* For controlled-source applications (SSM), leave each group/source pair
 */                    
                case SocketOptions.PGM_LEAVE_SOURCE_GROUP:
                        // key.drop();
                        return false;

/* batch block and unblock sources */                    
                case SocketOptions.PGM_MSFILTER:
                        return false;

/* UDP encapsulation ports */                    
                case SocketOptions.PGM_UDP_ENCAP_UCAST_PORT:
                        if (!(optval instanceof Integer))
                                break;
                        this.udpEncapsulationUnicastPort = (((Integer)optval).intValue());
                        return true;

                case SocketOptions.PGM_UDP_ENCAP_MCAST_PORT:
                        if (!(optval instanceof Integer))
                                break;
                        this.udpEncapsulationMulticastPort = (((Integer)optval).intValue());
                        return true;
                    
                default:
                        break;
                }
                
                return false;
        }
        
        public boolean connect() {
                if (this.isConnected || !this.isBound || this.isDestroyed) {
                        return false;
                }
                    
                LOG.debug ("connect ()");

                {
                        this.nextPoll = System.currentTimeMillis() + (30 * 1000);
                }
                
                this.isConnected = true;
                
/* Cleanup */
                LOG.debug ("PGM socket successfully connected.");
                return true;
        }
        
        public SelectionKey register (Selector selector, int op) throws ClosedChannelException {
                return this.recv_sock.register (selector, op);
        }
                
	public IoStatus receive (List<SocketBuffer> skbs) throws IOException {
		IoStatus status = IoStatus.IO_STATUS_WOULD_BLOCK;

		if (this.isReset) {
			if (!this.shouldAbortOnReset)
				this.isReset = !this.isReset;
			return IoStatus.IO_STATUS_RESET;
		}

/* timer status */
		if (timerCheck() &&
		    !timerDispatch())
		{
/* block on send-in-recv */
			status = IoStatus.IO_STATUS_RATE_LIMITED;
		}

		if (0 == ++(this.lastCommit))
			++(this.lastCommit);

		do {
/* second, flush any remaining contiguous messages from previous call(s) */
			if (!this.peers_pending.isEmpty()) {
				if (0 != flushPeersPending (skbs))
					break;
			}
		
			do {
				InetSocketAddress src = (InetSocketAddress)this.recv_sock.receive (this.buffer);
				this.buffer.flip();
				this.rx_buffer = new SocketBuffer (this.buffer.remaining());
				this.rx_buffer.put (this.rx_buffer.getRawBytes().length);
				this.buffer.get (this.rx_buffer.getRawBytes(), 0, this.rx_buffer.getRawBytes().length);
				this.buffer.clear();
				this.rx_buffer.setTimestamp (System.currentTimeMillis());
/* Rx testing */
if (Math.random() < 0.25) {
	LOG.info ("Dropping packet.");
	continue;
}
				if (!Packet.parseUdpEncapsulated (this.rx_buffer))
					break;
				this.source[0] = null;
				if (!onPgm (this.rx_buffer, src.getAddress(), this.recv_gsr.getFirst().getMulticastAddress(), this.source))
					break;
/* check whether this source has waiting data */
				if (null != this.source[0] && this.source[0].hasPending()) {
					LOG.info ("New pending data.");
					this.peers_pending.addFirst (this.source[0]);
					this.source[0].setPendingLinkData();
				}
/* flush any congtiguous packets generated by the receipt of this packet */
				if (!this.peers_pending.isEmpty()) {
					if (0 != flushPeersPending (skbs))
						break;
				}
			} while (false);
		} while (false);

		if (skbs.isEmpty()) {
			if (this.isReset) {
				if (!this.shouldAbortOnReset)
					this.isReset = !this.isReset;
				return IoStatus.IO_STATUS_RESET;
			}
			if (IoStatus.IO_STATUS_WOULD_BLOCK == status &&
				!this.peers_hashtable.isEmpty())
			{
				return IoStatus.IO_STATUS_TIMER_PENDING;
			}
			return status;
		}

		return IoStatus.IO_STATUS_NORMAL;
	}

	private boolean onUpstream (
		SocketBuffer skb,
		InetAddress sourceAddress,
		InetAddress destinationAddress
		)
	{
		return false;
	}

	private boolean onPeer (
		SocketBuffer skb,
		InetAddress sourceAddress,
		InetAddress destinationAddress
		)
	{
		return false;
	}

	private boolean onDownstream (
		SocketBuffer skb,
		InetAddress sourceAddress,
		InetAddress destinationAddress,
		Peer[] source
		)
	{
		if (!this.canReceiveData) {
			LOG.info ("Discarded packet for muted receiver.");
			return false;
		}

		if (skb.getHeader().getDestinationPort() != this.dataDestinationPort) {
			LOG.info ("Discarded packet on data-destination port mismatch.");
			LOG.info ("Data-destination port: {}", skb.getHeader().getDestinationPort());
			return false;
		}

		TransportSessionId tsi = skb.getHeader().getTransportSessionId();
		source[0] = this.peers_hashtable.get (tsi);
		if (null == source[0]) {
			source[0] = new Peer (tsi, this.max_tpdu, this.rxw_sqns, this.rxw_secs, this.rxw_max_rte);
			this.peers_hashtable.put (tsi, source[0]);
		}

		source[0].setLastPacketTimestamp (skb.getTimestamp());

		skb.pull (Packet.SIZEOF_PGM_HEADER);

		switch (skb.getHeader().getType()) {
		case Packet.PGM_RDATA:
			LOG.info ("********* REPAIR DATA ***************");
		case Packet.PGM_ODATA:
			if (!this.onData (source[0], skb))
				return false;
			break;

		case Packet.PGM_NCF:
			if (!this.onNakConfirm (source[0], skb))
				return false;
			break;

		case Packet.PGM_SPM:
			if (!this.onSourcePathMessage (source[0], skb))
				return false;
			if (destinationAddress.isMulticastAddress())
				source[0].setGroupAddress (destinationAddress);
			break;

		default:
			LOG.info ("Discarded unsupported PGM type packet.");
			return false;
		}

		return true;
	}

	private boolean onPgm (
		SocketBuffer skb,
		InetAddress sourceAddress,
		InetAddress destinationAddress,
		Peer[] source			/* reference to a peer */
		)
	{
		if (skb.getHeader().isDownstream())
			return this.onDownstream (skb, sourceAddress, destinationAddress, source);
		if (skb.getHeader().getDestinationPort() == this.tsi.getSourcePort())
		{
			if (skb.getHeader().isUpstream() || skb.getHeader().isPeer())
			{
				return this.onUpstream (skb, sourceAddress, destinationAddress);
			}
		}
		else if (skb.getHeader().isPeer())
			return this.onPeer (skb, sourceAddress, destinationAddress);

		LOG.info ("Discarded unknown PGM packet.");
		return false;
	}

	private boolean onData (
		Peer source,
		SocketBuffer skb
		)
	{
		int msgCount = 0;
		boolean flushNaks = false;

		LOG.info ("onData");

		final long nakBackoffExpiration = skb.getTimestamp() + calculateNakRandomBackoffInterval();

		skb.setOriginalDataOffset (skb.getDataOffset());

		final int opt_total_length = skb.getAsOriginalData().getOptionTotalLength();

/* advance data pointer to payload */
		skb.pull (OriginalData.SIZEOF_DATA_HEADER + opt_total_length);

		if (opt_total_length > 0)
			Packet.parseOptionExtensions (skb, skb.getDataOffset());

		final ReceiveWindow.Returns addStatus = source.add (skb, skb.getTimestamp(), nakBackoffExpiration);
LOG.info ("ReceiveWindow.add returned " + addStatus);

		switch (addStatus) {
		case RXW_MISSING:
			flushNaks = true;
		case RXW_INSERTED:
		case RXW_APPENDED:
			msgCount++;
			break;

		case RXW_DUPLICATE:
		case RXW_MALFORMED:
		case RXW_BOUNDS:
			return false;
		}

		if (flushNaks) {
/* flush out 1st time nak packets */
			if (flushNaks && this.nextPoll > nakBackoffExpiration)
				this.nextPoll = nakBackoffExpiration;
		}
		return true;
	}

	private boolean onNakConfirm (
		Peer source,
		SocketBuffer skb
		)
	{
		return true;
	}

	private boolean onSourcePathMessage (
		Peer source,
		SocketBuffer skb
		)
	{
		LOG.info ("onSourcePathMessage");

		SourcePathMessage spm = new SourcePathMessage (skb, skb.getDataOffset());

/* check for advancing sequence number, or first SPM */
/* BUG: cannot join old networks */
		if (spm.getSpmSequenceNumber().gte (source.getSpmSequenceNumber()))
		{
/* copy NLA for replies */
			source.setNetworkLayerAddress (spm.getSpmNla());

/* save sequence number */
			source.setSpmSequenceNumber (spm.getSpmSequenceNumber());

/* update receive window */
			final long nak_rb_expiry = skb.getTimestamp() + calculateNakRandomBackoffInterval();
			final int naks = source.update (spm.getSpmLead(),
							spm.getSpmTrail(),
							skb.getTimestamp(),
							nak_rb_expiry);
			if (naks > 0) {
				if (this.nextPoll > nak_rb_expiry)
					this.nextPoll = nak_rb_expiry;
			}

/* mark receiver window for flushing on next recv() */
			if (source.hasDataLoss() &&
			    !source.hasPendingLinkData())
			{
				this.isReset = true;
				source.clearDataLoss();
				setPendingPeer (source);
			}
		}
		else
		{	/* does not advance SPM sequence number */
			LOG.info ("Discarded duplicate SPM.");
			return false;
		}

/* either way bump expiration timer */
		source.setExpiration (skb.getTimestamp() + this.peerExpiration);
		source.clearSpmrExpiration();
		return true;
	}

/* copy any contiguous buffers in the peer list to the provided buffer.
 */
	private int flushPeersPending (
			List<SocketBuffer> skbs
			)
	{
LOG.info ("flushPeersPending");
		int bytes_read = 0;
		int data_read = 0;
		ListIterator<Peer> it = this.peers_pending.listIterator();
		while (it.hasNext()) {
			Peer peer = it.next();
			if (peer.hasLastCommit() && peer.getLastCommit() < this.lastCommit)
				peer.removeCommit();
			final int peer_bytes = peer.read (skbs);
			if (peer.hasDataLoss())
			{
				this.isReset = true;
				peer.clearDataLoss();
			}
			if (peer_bytes > 0) {
				bytes_read += peer_bytes;
				data_read++;
				peer.setLastCommit (this.lastCommit);
			} else
				peer.setLastCommit (0);
			if (this.isReset)
				return -1;
/* clear this reference and move to next */
			this.peers_pending.remove();
			peer.clearPendingLinkData();
		}
		return bytes_read;
	}

	private boolean timerCheck()
	{
		final long now = System.currentTimeMillis();
		final boolean hasExpired = now >= this.nextPoll;
LOG.info ("now: {} next: {}", now, (this.nextPoll - now) / 1000);
		return hasExpired;
	}

	private boolean timerDispatch()
	{
		final long now = System.currentTimeMillis();
		long nextExpiration = 0;

		LOG.info ("timerDispatch");

		if (true) {
			if (!checkPeerState (now))
				return false;
			nextExpiration = minReceiverExpiration (now + this.peerExpiration);
		}

		if (false) {
		}
		else
			this.nextPoll = nextExpiration;

		return true;
	}

	private boolean checkPeerState (long now)
	{
		LOG.info ("checkPeerState");

		if (this.peers_hashtable.isEmpty())
			return true;

		for (Enumeration<Peer> it = this.peers_hashtable.elements(); it.hasMoreElements();)
		{
			Peer peer = it.nextElement();
			if (peer.hasSpmrExpiration() &&
			    now >= peer.getSpmrExpiration())
			{
				if (!sendSpmr (peer))
					return false;
				peer.clearSpmrExpiration();
			}

			if (!peer.getNakBackoffQueue().isEmpty() &&
			    now >= peer.firstNakBackoffExpiration())
			{
				if (!nakBackoffState (peer, now))
					return false;
			}

			if (!peer.getWaitNakConfirmQueue().isEmpty() &&
			    now >= peer.firstNakRepeatExpiration())
			{
				nakRepeatState (peer, now);
			}

			if (!peer.getWaitDataQueue().isEmpty() &&
			    now >= peer.firstRepairDataExpiration())
			{
				nakRepairDataState (peer, now);
			}

/* expired, remove from hash table and linked list */
			if (now >= peer.getExpiration())
			{
				if (peer.hasPendingLinkData())
				{
					LOG.info ("Peer expiration postponed due to committing data.");
					peer.setExpiration (peer.getExpiration() + this.peerExpiration);
				}
				else if (peer.hasCommitData())
				{
					LOG.info ("Peer expiration postoned due to comitted data.");
					peer.setExpiration (peer.getExpiration() + this.peerExpiration);
				}
				else
				{
					LOG.info ("Peer expired.");
					this.peers_hashtable.remove (peer.getTransportSessionId());
					peer = null;
				}
			}
		}

/* check for waiting contiguous packets */
		if (!this.peers_pending.isEmpty() && !this.hasPendingRead)
		{
			LOG.info ("Signal receiver thread.");
			this.hasPendingRead = true;
		}

		return true;
	}

	private long minReceiverExpiration (long expiration)
	{
		LOG.info ("minReceiverExpiration");

		if (this.peers_hashtable.isEmpty())
			return expiration;

		for (Enumeration<Peer> it = this.peers_hashtable.elements(); it.hasMoreElements();)
		{
			Peer peer = it.nextElement();
			if (peer.hasSpmrExpiration() &&
			    expiration >= peer.getSpmrExpiration())
			{
LOG.info ("Next expiration: SPMR");
				expiration = peer.getSpmrExpiration();
			}

			if (!peer.getNakBackoffQueue().isEmpty() &&
			    expiration >= peer.firstNakBackoffExpiration())
			{
LOG.info ("Next expiration: NAK backoff");
				expiration = peer.firstNakBackoffExpiration();
			}

			if (!peer.getWaitNakConfirmQueue().isEmpty() &&
			    expiration >= peer.firstNakRepeatExpiration())
			{
LOG.info ("Next expiration: NAK repeat");
				expiration = peer.firstNakRepeatExpiration();
			}

			if (!peer.getWaitDataQueue().isEmpty() &&
			    expiration >= peer.firstRepairDataExpiration())
			{
LOG.info ("Next expiration: RDATA");
				expiration = peer.firstRepairDataExpiration();
			}
		}

		return expiration;
	}

	private boolean nakBackoffState (Peer peer, long now)
	{
		int droppedInvalid = 0;

		LOG.info ("nakBackoffState");

		Queue<SocketBuffer> nakBackoffQueue = peer.getNakBackoffQueue();
		if (nakBackoffQueue.isEmpty()) {
			LOG.info ("Backoff queue is empty in nak_rb_state.");
			return true;
		}

		final boolean isValidNla = peer.hasValidNla();

		{
			ArrayList<SequenceNumber> nakList = new ArrayList<>();

/* select NAK generation */

LOG.info ("nakBackoffQueue contains {} SKBs.", nakBackoffQueue.size());
			for (Iterator<SocketBuffer> it = nakBackoffQueue.iterator(); it.hasNext();)
			{
				SocketBuffer skb = it.next();

				if (now >= ReceiveWindow.getNakBackoffExpiration (skb))
				{
					if (!isValidNla) {
						droppedInvalid++;
						peer.markLost (skb.getSequenceNumber());
						setPendingPeer (peer);
						continue;
					}

					peer.setWaitNakConfirmState (skb);
					nakList.add (skb.getSequenceNumber());
					ReceiveWindow.incrementNakTransmitCount (skb);

					ReceiveWindow.setNakRepeatExpiration (skb, now + this.nak_rpt_ivl);
					LOG.info ("nak_rpt_expiry in {} seconds.", ((ReceiveWindow.getNakRepeatExpiration (skb) - now) / 1000));
					if (this.nextPoll > ReceiveWindow.getNakRepeatExpiration (skb))
						this.nextPoll = ReceiveWindow.getNakRepeatExpiration (skb);

					if (nakList.size() == 63) {
						if (!sendNakList (peer, nakList))
							return false;
						nakList.clear();
					}
				}
				else
				{	/* packet expires some time later */
LOG.info ("SKB expiration now + {}", (ReceiveWindow.getNakBackoffExpiration (skb) - now));
					break;
				}
			}

			if (!nakList.isEmpty()) {
				if (nakList.size() > 1 && !sendNakList (peer, nakList))
					return false;
				else if (!sendNak (peer, nakList.get (0)))
					return false;
			}
		}

		if (droppedInvalid > 0)
		{
			LOG.info ("Dropped {} messages due to invalid NLA.", droppedInvalid);

			if (peer.hasDataLoss() &&
			    !peer.hasPendingLinkData())
			{
				this.isReset = true;
				peer.clearDataLoss();
				setPendingPeer (peer);
			}
		}

		if (!nakBackoffQueue.isEmpty()) {
			final long secs = (peer.firstNakBackoffExpiration() - now) / 1000;
			LOG.info ("Next expiration set in {} seconds.", secs);
		} else {
			LOG.info ("NAK backoff queue empty.");
		}
		return true;
	}

	private void nakRepeatState (Peer peer, long now)
	{
		int droppedInvalid = 0;
		int dropped = 0;

		LOG.info ("NakRepeatState");

		Queue<SocketBuffer> waitNakConfirmQueue = peer.getWaitNakConfirmQueue();

		final boolean isValidNla = peer.hasValidNla();

LOG.info ("waitNcfQueue contains {} SKBs.", waitNakConfirmQueue.size());
		for (Iterator<SocketBuffer> it = waitNakConfirmQueue.iterator(); it.hasNext();)
		{
			SocketBuffer skb = it.next();

/* check this packet for state expiration */
			if (now >= ReceiveWindow.getNakRepeatExpiration (skb))
			{
				if (!isValidNla) {
					droppedInvalid++;
					peer.markLost (skb.getSequenceNumber());
/* mark receiver window for flushing on next recv() */
					setPendingPeer (peer);
					continue;
				}

				ReceiveWindow.incrementNcfRetryCount (skb);
				if (ReceiveWindow.getNcfRetryCount (skb) >= this.nak_ncf_retries)
				{
					dropped++;
					cancel (peer, skb, now);
				}
				else
				{
/* retry */
					ReceiveWindow.setNakBackoffExpiration (skb, now + calculateNakRandomBackoffInterval());
					peer.setBackoffState (skb);
					LOG.info ("NCF retry #{} attempt {}/{}.",
                                                   skb.getSequenceNumber(),
                                                   ReceiveWindow.getNcfRetryCount (skb),
                                                   this.nak_ncf_retries);
				}
			}
			else
			{
/* packet expires some time later */
				final long seconds = (ReceiveWindow.getNakRepeatExpiration (skb) - now) / 1000;
				LOG.info ("NCF retry #{} is delayed {} seconds.",
                                           skb.getSequenceNumber(), seconds);
			}
		}

		if (droppedInvalid > 0)
			LOG.info ("Dropped {} message due to invalid NLA.", droppedInvalid);

		if (dropped > 0)
			LOG.info ("Dropped messages due to NCF cancellation.", dropped);

		if (peer.hasDataLoss() &&
		    !peer.hasPendingLinkData())
		{
			this.isReset = true;
			peer.clearDataLoss();
			setPendingPeer (peer);
		}

		if (!waitNakConfirmQueue.isEmpty())
		{
			if (peer.firstNakRepeatExpiration() > now) {
				final long seconds = (peer.firstNakRepeatExpiration() - now) / 1000;
				LOG.info ("Next expiration set in {} seconds.", seconds);
			} else {
				final long seconds = (now - peer.firstNakRepeatExpiration()) / 1000;
				LOG.info ("Next expiration set in -{} seconds.", seconds);
			}
		}
		else
		{
			LOG.info ("Wait NCF queue empty.");
		}
	}

	private void nakRepairDataState (Peer peer, long now)
	{
		int droppedInvalid = 0;
		int dropped = 0;

		LOG.info ("nakRepairDataState");

		Queue<SocketBuffer> waitDataQueue = peer.getWaitDataQueue();

		final boolean isValidNla = peer.hasValidNla();

LOG.info ("waitDataQueue contains {} SKBs.", waitDataQueue.size());
		for (Iterator<SocketBuffer> it = waitDataQueue.iterator(); it.hasNext();)
		{
			SocketBuffer skb = it.next();

/* check this packet for state expiration */
			if (now >= ReceiveWindow.getRepairDataExpiration (skb))
			{
				if (!isValidNla) {
					droppedInvalid++;
					peer.markLost (skb.getSequenceNumber());
/* mark receiver window for flushing on next recv() */
					setPendingPeer (peer);
					continue;
				}

				ReceiveWindow.incrementDataRetryCount (skb);
				if (ReceiveWindow.getDataRetryCount (skb) >= this.nak_data_retries)
				{
					dropped++;
					cancel (peer, skb, now);
				}
				else
				{
/* retry */
					ReceiveWindow.setNakBackoffExpiration (skb, now + calculateNakRandomBackoffInterval());
					peer.setBackoffState (skb);
					LOG.info ("Data retry #{} attempt {}/{}.",
                                                   skb.getSequenceNumber(),
                                                   ReceiveWindow.getDataRetryCount (skb),
                                                   this.nak_data_retries);
				}
			}
			else
			{
/* packet expires some time later */
				break;
			}
		}

		if (droppedInvalid > 0)
			LOG.info ("Dropped {} message due to invalid NLA.", droppedInvalid);

		if (dropped > 0)
			LOG.info ("Dropped {} messages due to data cancellation.", dropped);

		if (peer.hasDataLoss() &&
		    !peer.hasPendingLinkData())
		{
			this.isReset = true;
			peer.clearDataLoss();
			setPendingPeer (peer);
		}

		if (!waitDataQueue.isEmpty()) {
			final long seconds = (peer.firstRepairDataExpiration() - now) / 1000;
			LOG.info ("Next expiration set in {} seconds.", seconds);
		} else {
			LOG.info ("Wait data queue empty.");
		}
	}

	private void setPendingPeer (Peer peer)
	{
		if (peer.hasPendingLinkData()) return;
		this.peers_pending.addFirst (peer);
		peer.setPendingLinkData();
	}

	private boolean sendSpmr (Peer peer)
	{
		LOG.info ("sendSpmr");

		SocketBuffer skb = SourcePathMessageRequest.create();
		Header header = skb.getHeader();
		header.setGlobalSourceId (peer.getTransportSessionId().getGlobalSourceId());
/* dport & sport reversed communicating upstream */
		header.setSourcePort (this.dataDestinationPort);
		header.setDestinationPort (peer.getSourcePort());
		header.setChecksum (Packet.doChecksum (skb.getRawBytes()));

/* Send multicast SPMR TTL 1 to our peers listening on the same groups */                
		DatagramPacket pkt = new DatagramPacket (skb.getRawBytes(),
							 skb.getDataOffset(),
							 skb.getLength(),
							 null,
							 this.udpEncapsulationMulticastPort);
                try {                            
                        this.send_sock.setTimeToLive (1);
                        for (GroupSourceRequest gsr : this.recv_gsr) {
                                pkt.setAddress (gsr.getMulticastAddress());
/* Ignore errors on peer multicast */
                                this.send_sock.send (pkt);
                        }
                } catch (java.io.IOException e) {}

/* Send unicast SPMR with regular TTL */                
                try {
                        pkt.setAddress (peer.getNetworkLayerAddress());
			this.send_sock.send (pkt);
		} catch (java.io.IOException e) {
			LOG.error (e.toString());
			return false;
		}
                
                return true;
	}

	private boolean sendNak (Peer peer, SequenceNumber sequence)
	{
		LOG.info ("sendNak");

		SocketBuffer skb = Nak.create (peer.getNetworkLayerAddress(), peer.getGroupAddress());
		Header header = skb.getHeader();
		Nak nak = new Nak (skb, skb.getDataOffset());
		header.setGlobalSourceId (peer.getTransportSessionId().getGlobalSourceId());

/* dport & sport swap over for a nak */
		header.setSourcePort (this.dataDestinationPort);
		header.setDestinationPort (peer.getSourcePort());

/* NAK */
		nak.setNakSequenceNumber (sequence);

/* source nla */
		nak.setNakSourceNla (peer.getNetworkLayerAddress());

/* group nla: we match the NAK NLA to the same as advertised by the source, we might
 * be listening to multiple multicast groups
 */
		nak.setNakGroupNla (peer.getGroupAddress());

		header.setChecksum (Packet.doChecksum (skb.getRawBytes()));

		DatagramPacket pkt = new DatagramPacket (skb.getRawBytes(),
							 0,
							 skb.getRawBytes().length,
							 peer.getNetworkLayerAddress(),
							 this.udpEncapsulationUnicastPort);
		try {
			this.send_sock.send (pkt);
			LOG.info ("Sent NAK to {}", peer.getNetworkLayerAddress());
			LOG.info ("NAK: {}", skb);
			return true;
		} catch (java.io.IOException e) {
			LOG.error (e.toString());
			return false;
		}	
	}

	private boolean sendNakList (Peer peer, ArrayList<SequenceNumber> sqn_list)
	{
		LOG.info ("sendNakList");

		SocketBuffer skb = Nak.create (peer.getNetworkLayerAddress(), peer.getGroupAddress(), sqn_list.size());
		Header header = skb.getHeader();
		Nak nak = new Nak (skb, skb.getDataOffset());
		header.setGlobalSourceId (peer.getTransportSessionId().getGlobalSourceId());

/* dport & sport swap over for a nak */
		header.setSourcePort (this.dataDestinationPort);
		header.setDestinationPort (peer.getSourcePort());
		header.setOptions (Packet.PGM_OPT_PRESENT | Packet.PGM_OPT_NETWORK);

/* NAK */
		nak.setNakSequenceNumber (sqn_list.get (0));

/* source nla */
		nak.setNakSourceNla (peer.getNetworkLayerAddress());

/* group nla */
		nak.setNakGroupNla (peer.getGroupAddress());

/* OPT_NAK_LIST */
		nak.setNakListOption (sqn_list.subList (1, sqn_list.size()).toArray (new SequenceNumber[0]));

		header.setChecksum (Packet.doChecksum (skb.getRawBytes()));

		DatagramPacket pkt = new DatagramPacket (skb.getRawBytes(),
							 0,
							 skb.getRawBytes().length,
							 peer.getNetworkLayerAddress(),
							 this.udpEncapsulationUnicastPort);
		try {
			this.send_sock.send (pkt);
			LOG.info ("Sent NAK to {}", peer.getNetworkLayerAddress());
			return true;
		} catch (java.io.IOException e) {
			LOG.error (e.toString());
			return false;
		}
	}

	private void cancel (Peer peer, SocketBuffer skb, long now)
	{
		LOG.info ("Lost data #{} due to cancellation.", skb.getSequenceNumber());

		peer.markLost (skb.getSequenceNumber());

/* mark receiver window for flushing on next recv() */
		setPendingPeer (peer);
	}

/* The java Math library function Math.random() generates a double value in the
 * range [0,1). Notice this range does not include the 1.
 * Reference: http://stackoverflow.com/a/363732/175849
 */
	private long randomIntRange (long begin, long end)
	{
		return begin + (long)(Math.random() * ((end - begin) + 1));
	}

/* calculate NAK_RB_IVL as random time interval 1 - NAK_BO_IVL.
 */
	private long calculateNakRandomBackoffInterval()
	{
		return randomIntRange (1 /* us */, this.nak_bo_ivl);
	}
}

/* eof */