/*
 * Copyright (C) 2010 AVM GmbH <info@avm.de>
 * Copyright (C) 2009 The Sipdroid Open Source Project
 * Copyright (C) 2005 Luca Veltri - University of Parma - Italy
 * 
 * This file is part of Sipdroid (http://www.sipdroid.org)
 * 
 * Sipdroid is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This source code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this source code; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package de.avm.android.fritzapp.sipua;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Vector;

import org.sipdroid.media.JAudioLauncher;
import org.sipdroid.media.MediaLauncher;
import org.zoolu.net.IpAddress;
import org.zoolu.sdp.AttributeField;
import org.zoolu.sdp.ConnectionField;
import org.zoolu.sdp.MediaDescriptor;
import org.zoolu.sdp.MediaField;
import org.zoolu.sdp.SessionDescriptor;
import org.zoolu.sdp.TimeField;
import org.zoolu.sip.address.NameAddress;
import org.zoolu.sip.call.Call;
import org.zoolu.sip.call.CallListenerAdapter;
import org.zoolu.sip.call.ExtendedCall;
import org.zoolu.sip.call.SdpTools;
import org.zoolu.sip.header.StatusLine;
import org.zoolu.sip.message.Message;
import org.zoolu.sip.provider.SipProvider;
import org.zoolu.sip.provider.SipStack;
import org.zoolu.tools.Log;
import org.zoolu.tools.LogLevel;
import org.zoolu.tools.Parser;

import android.content.Context;
import android.media.MediaPlayer;
import android.telephony.TelephonyManager;
import de.avm.android.fritzapp.R;
import de.avm.android.fritzapp.sipua.ui.Receiver;
import de.avm.android.fritzapp.sipua.ui.Sipdroid;

/**
 * Simple SIP user agent (UA). It includes audio/video applications.
 * <p>
 * It can use external audio/video tools as media applications. Currently only
 * RAT (Robust Audio Tool) and VIC are supported as external applications.
 */
public class UserAgent extends CallListenerAdapter {
	/** Event logger. */
	Log log;

	/** UserAgentProfile */
	public UserAgentProfile user_profile;

	/** SipProvider */
	protected SipProvider sip_provider;

	/** Call */
	// Call call;
	protected ExtendedCall call;

	/** Call transfer */
	protected ExtendedCall call_transfer;

	/** Audio application */
	protected MediaLauncher audio_app = null;

	/** Local sdp */
	protected String local_session = null;
	
	public static final int UA_STATE_IDLE = 0;
	public static final int UA_STATE_INCOMING_CALL = 1;
	public static final int UA_STATE_OUTGOING_CALL = 2;
	public static final int UA_STATE_INCALL = 3;
	public static final int UA_STATE_HOLD = 4;

	int call_state = UA_STATE_IDLE;
	String remote_media_address;
	int remote_video_port,local_video_port,payload_type;

	// *************************** Basic methods ***************************

	/** Changes the call state */
	protected synchronized void changeStatus(int state,String caller) {
		call_state = state;
		Receiver.onState(state, caller);
	}
	
	protected void changeStatus(int state) {
		changeStatus(state, null);
	}

	/** Checks the call state */
	protected boolean statusIs(int state) {
		return (call_state == state);
	}

	/**
	 * Sets the automatic answer time (default is -1 that means no auto accept
	 * mode)
	 */
	public void setAcceptTime(int accept_time) {
		user_profile.accept_time = accept_time;
	}

	/**
	 * Sets the automatic hangup time (default is 0, that corresponds to manual
	 * hangup mode)
	 */
	public void setHangupTime(int time) {
		user_profile.hangup_time = time;
	}

	/** Sets the redirection url (default is null, that is no redircetion) */
	public void setRedirection(String url) {
		user_profile.redirect_to = url;
	}

	/** Sets the no offer mode for the invite (default is false) */
	public void setNoOfferMode(boolean nooffer) {
		user_profile.no_offer = nooffer;
	}

	/** Enables audio */
	public void setAudio(boolean enable) {
		user_profile.audio = enable;
	}

	/** Sets the receive only mode */
	public void setReceiveOnlyMode(boolean r_only) {
		user_profile.recv_only = r_only;
	}

	/** Sets the send only mode */
	public void setSendOnlyMode(boolean s_only) {
		user_profile.send_only = s_only;
	}

	/** Sets the send tone mode */
	public void setSendToneMode(boolean s_tone) {
		user_profile.send_tone = s_tone;
	}

	/** Sets the send file */
	
	public void setSendFile(String file_name) {
		user_profile.send_file = file_name;
	}

	/** Sets the recv file */
	
	public void setRecvFile(String file_name) {
		user_profile.recv_file = file_name;
	}
	
	/** Gets the local SDP */
	public String getSessionDescriptor() {
		return local_session;
	}

	//change start (multi codecs)
	/** Inits the local SDP (no media spec) */
	public void initSessionDescriptor(int[] audio_codecs) {
		SessionDescriptor sdp = new SessionDescriptor(
				user_profile.from_url,
				sip_provider.getViaAddress());
		
		local_session = sdp.toString();
		
		//We will have at least one media line, and it will be 
		//audio
		if (user_profile.audio || !user_profile.video)
		{
			addMediaDescriptor("audio", user_profile.audio_port, audio_codecs, user_profile.audio_sample_rate);
		}
		
		if (user_profile.video)
		{
			addMediaDescriptor("video", user_profile.video_port,
					user_profile.video_avp, "h263-1998", 90000);
		}
	}
	//change end
	
	/** Adds a media to the SDP */
	public void addMediaDescriptor(String media, int port, int avp,
			String codec, int rate) {
		SessionDescriptor sdp = new SessionDescriptor(local_session);
		
		String attr_param = String.valueOf(avp);
		
		if (codec != null)
		{
			attr_param += " " + codec + "/" + rate;
		}
		sdp.addMedia(new MediaField(media, port, 0, "RTP/AVP", 
				String.valueOf(avp)), 
				new AttributeField("rtpmap", attr_param));
		
		local_session = sdp.toString();
	}
	
	protected static HashMap<Integer,String> codecs = new HashMap<Integer,String>(){{
		put(0,"PCMU");
		put(3,"GSM");
		put(8,"PCMA");
		put(103,"h263-1998");
	}};
	
	/** Adds a media to the SDP */
	public void addMediaDescriptor(String media, int port, int[] avps, int rate) {
		SessionDescriptor sdp = new SessionDescriptor(local_session);
	
		Vector<String> avpvec = new Vector<String>();
		Vector<AttributeField> afvec = new Vector<AttributeField>();
		for (int i=0; i<avps.length ; i++) {
			int avp = avps[i];
			String codec = codecs.get(avp);
			if (codec!=null) {
				avpvec.add(String.valueOf(avp));
				afvec.add(new AttributeField("rtpmap", String.format("%d %s/%d", avp, codec, rate)));
			}
		}
		if(user_profile.dtmf_avp != 0) {
			avpvec.add(String.valueOf(user_profile.dtmf_avp));
			afvec.add(new AttributeField("rtpmap", String.format("%d telephone-event/%d", user_profile.dtmf_avp, rate)));
			afvec.add(new AttributeField("fmtp", String.format("%d 0-15", user_profile.dtmf_avp)));
		}
		//String attr_param = String.valueOf(avp);
		
		sdp.addMedia(new MediaField(media, port, 0, "RTP/AVP", avpvec), afvec);
		
		local_session = sdp.toString();
	}

	// *************************** Public Methods **************************

	/** Costructs a UA with a default media port */
	public UserAgent(SipProvider sip_provider, UserAgentProfile user_profile) {
		this.sip_provider = sip_provider;
		log = sip_provider.getLog();
		this.user_profile = user_profile;
		realm = user_profile.realm;
		
		// if no contact_url and/or from_url has been set, create it now
		user_profile.initContactAddress(sip_provider);
	}

	String realm;
	
	/** Makes a new call (acting as UAC). */
	public boolean call(String target_url, boolean send_anonymous) {
		
		if (!this.statusIs(UA_STATE_IDLE))
		{
			//We can initiate or terminate a call only when
			//we are in an idle state
			printLog("Call attempted in state" + this.getSessionDescriptor() + " : Failing Request", LogLevel.HIGH);
			return false;
		}
		hangup(); // modified
		changeStatus(UA_STATE_OUTGOING_CALL,target_url);
		
		String from_url;
		
		if (!send_anonymous)
		{
			from_url = user_profile.callerid;
		}
		else
		{
			from_url = "sip:anonymous@anonymous.com";
		}

		//change start multi codecs
		createOffer(useAudioCompression());
		//change end
		call = new ExtendedCall(sip_provider, from_url,
				user_profile.contact_url, user_profile.username,
				user_profile.realm, user_profile.passwd, this);
		
		// in case of incomplete url (e.g. only 'user' is present), try to
		// complete it
		if (target_url.indexOf("@") < 0) {
			if (user_profile.realm.equals("pbxes.org"))
				target_url = "&" + target_url;
			target_url = target_url + "@" + realm; // modified
		}
		
		target_url = sip_provider.completeNameAddress(target_url).toString();
		
		if (user_profile.no_offer)
		{
			call.call(target_url);
		}
		else
		{
			call.call(target_url, local_session);
		}
		
		return true;
	}

	private boolean useAudioCompression() {
		// never
		return false;
	}

	public void info(char c, int duration)
	{
        boolean use2833 = audio_app != null && audio_app.sendDTMF(c); // send out-band DTMF (rfc2833) if supported

        if (!use2833 && call != null)
            call.info(c);
	}
	
	/** Waits for an incoming call (acting as UAS). */
	public boolean listen() {
		
		if (!this.statusIs(UA_STATE_IDLE))
		{
			//We can listen for a call only when
			//we are in an idle state
			printLog("Call listening mode initiated in " + this.getSessionDescriptor() + " : Failing Request", LogLevel.HIGH);
			return false;
		}
		
		hangup();
		
		call = new ExtendedCall(sip_provider, user_profile.from_url,
				user_profile.contact_url, user_profile.username,
				user_profile.realm, user_profile.passwd, this);
		call.listen();
		
		return true;
	}

	/** Closes an ongoing, incoming, or pending call */
	public void hangup() 
	{
		printLog("HANGUP");
		closeMediaApplication();
		
		if (call != null)
		{
			call.hangup();
		}
		
		changeStatus(UA_STATE_IDLE);
	}

	/** Accepts an incoming call */
	public boolean accept() 
	{
		if (call == null)
		{
			return false;
		}
		
		changeStatus(UA_STATE_INCALL);
		
		call.accept(local_session);
		
		return true;
	}

	/** Redirects an incoming call */
	public void redirect(String redirection) 
	{
		if (call != null)
		{
			call.redirect(redirection);
		}
	}

	/** Launches the Media Application (currently, the RAT audio tool) */
	protected boolean launchMediaApplication() {
		// exit if the Media Application is already running
		if (audio_app != null) {
			printLog("DEBUG: media application is already running", LogLevel.HIGH);
			return audio_app.mediaOk(false);
		}
		int avp = payload_type; //local_sdp must be set properly
		
		// parse local sdp
		SessionDescriptor local_sdp = new SessionDescriptor(call
				.getLocalSessionDescriptor());
		int local_audio_port = 0;
		local_video_port = 0;
		int dtmf_pt = 0;
		
		MediaDescriptor m = local_sdp.getMediaDescriptor("video");
		if(m!= null)
			local_video_port = m.getMedia().getPort();
		
		m = local_sdp.getMediaDescriptor("audio");
		if(m != null) {
			local_audio_port = m.getMedia().getPort();
			MediaField media = m.getMedia();
			if(media.getFormatList().contains(String.valueOf(user_profile.dtmf_avp)))
				dtmf_pt = user_profile.dtmf_avp;
			avp = Integer.valueOf(media.getFormatList().firstElement());
		}

		// parse remote sdp
		SessionDescriptor remote_sdp = new SessionDescriptor(call
				.getRemoteSessionDescriptor());
		remote_media_address = (new Parser(remote_sdp.getConnection()
				.toString())).skipString().skipString().getString();
		int remote_audio_port = 0;
		remote_video_port = 0;
		for (Enumeration<MediaDescriptor> e = remote_sdp.getMediaDescriptors()
				.elements(); e.hasMoreElements();) {
			MediaField media = e.nextElement().getMedia();
			if (media.getMedia().equals("audio"))
				remote_audio_port = media.getPort();
			if (media.getMedia().equals("video"))
				remote_video_port = media.getPort();
		}

		// select the media direction (send_only, recv_ony, fullduplex)
		int dir = 0;
		if (user_profile.recv_only)
			dir = -1;
		else if (user_profile.send_only)
			dir = 1;

		// create an audio_app and start it
		if (user_profile.audio && local_audio_port != 0	&& remote_audio_port != 0) {
			if (audio_app == null) { // for testing..
				String audio_in = null;
				if (user_profile.send_tone) {
					audio_in = JAudioLauncher.TONE;
				} else if (user_profile.send_file != null) {
					audio_in = user_profile.send_file;
				}
				String audio_out = null;
				if (user_profile.recv_file != null) {
					audio_out = user_profile.recv_file;
				}

				audio_app = new JAudioLauncher(local_audio_port,
						remote_media_address, remote_audio_port, dir, audio_in,
						audio_out, user_profile.audio_sample_rate,
						user_profile.audio_sample_size,
						user_profile.audio_frame_size, log, avp, dtmf_pt);
			}
			return audio_app.startMedia();
		}
		return !user_profile.audio;
	}

	/** Close the Media Application */
	protected void closeMediaApplication() {
		if (audio_app != null) {
			audio_app.stopMedia();
			audio_app = null;
		}
	}
	
	public boolean muteMediaApplication() {
		if (audio_app != null)
			return audio_app.muteMedia();
		return false;
	}

	public int speakerMediaApplication(int mode) {
		if (audio_app != null)
			return audio_app.speakerMedia(mode);
		return 0;
	}

	private void createOffer(boolean useGSM) {
		Vector<Integer> avpvec = new Vector<Integer>();
		for (int i=0;i<user_profile.codecs.length;i++){
			int avp = user_profile.codecs[i];
			if (avp == 3 && !useGSM) continue;
			avpvec.add(avp);
		}
		int[] avps = new int[avpvec.size()];
		for (int i=0;i<avps.length;i++){
			avps[i] = avpvec.elementAt(i);
		}
		initSessionDescriptor(avps);
	}

	private void createAnswer(SessionDescriptor remote_sdp, boolean useGSM) {
		Vector<String> remote_formats = remote_sdp.getMediaDescriptor("audio").getMedia().getFormatList();
				
		int avp = -1;
		for (int i=0;i<user_profile.codecs.length;i++) {
			avp = user_profile.codecs[i];
			if (avp == 3 && !useGSM) continue;
			if (remote_formats.contains(String.valueOf(avp))) break;
		}
		initSessionDescriptor(new int[]{avp});
		sessionProduct(remote_sdp);
	}

	private void sessionProduct(SessionDescriptor remote_sdp) {
		SessionDescriptor local_sdp = new SessionDescriptor(local_session);
		SessionDescriptor new_sdp = new SessionDescriptor(remote_sdp
				.getOrigin(), remote_sdp.getSessionName(), local_sdp
				.getConnection(), local_sdp.getTime());
		new_sdp.addMediaDescriptors(local_sdp.getMediaDescriptors());
		new_sdp = SdpTools.sdpMediaProduct(new_sdp, remote_sdp
				.getMediaDescriptors());
		//new_sdp = SdpTools.sdpAttirbuteSelection(new_sdp, "rtpmap"); ////change multi codecs
		local_session = new_sdp.toString();
	}

	private boolean gsmBusy() {
		TelephonyManager tm = (TelephonyManager)Receiver.mContext.getSystemService(Context.TELEPHONY_SERVICE);
		int callState = tm.getCallState();
		return (callState == TelephonyManager.CALL_STATE_RINGING) || (callState == TelephonyManager.CALL_STATE_OFFHOOK);
	}
	
	// ********************** Call callback functions **********************
	
	/**
	 * Callback function called when arriving a new INVITE method (incoming
	 * call)
	 */
	public void onCallIncoming(Call call, NameAddress callee,
			NameAddress caller, String sdp, Message invite) {
		printLog("onCallIncoming()", LogLevel.LOW);
		
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("INCOMING", LogLevel.HIGH);
		
		if (!Receiver.isFast() || gsmBusy()) {
			call.busy();
			listen();
			return;
		}
		
		changeStatus(UA_STATE_INCOMING_CALL,caller.toString());

		boolean useGSM = useAudioCompression();
		if (sdp == null) {
			createOffer(useGSM);
		}
		else { 
			SessionDescriptor remote_sdp = new SessionDescriptor(sdp);
			createAnswer(remote_sdp, useGSM);
		}
		call.ring(local_session);
		if(!launchMediaApplication()) { // NB: signal 'busy' on audio initialization errors.
			changeStatus(UA_STATE_IDLE,caller.toString());
			call.busy();
			listen();
			return;
		}
	}

	/**
	 * Callback function called when arriving a new Re-INVITE method
	 * (re-inviting/call modify)
	 */
	public void onCallModifying(Call call, String sdp, Message invite) 
	{
		printLog("onCallModifying()", LogLevel.LOW);
		if (call != this.call) 
		{
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("RE-INVITE/MODIFY", LogLevel.HIGH);

		// to be implemented.
		// currently it simply accepts the session changes (see method
		// onCallModifying() in CallListenerAdapter)
		super.onCallModifying(call, sdp, invite);
	}

	private void ring()
	{
		if (Receiver.ringbackPlayer == null || ! Receiver.ringbackPlayer.isPlaying()) {
			Receiver.ringbackPlayer = MediaPlayer.create(Receiver.mContext, R.raw.ringback);
			Receiver.ringbackPlayer.setLooping(true);
			Receiver.ringbackPlayer.start();
		}
	}

	/**
	 * Callback function that may be overloaded (extended). Called when arriving
	 * a 180 Ringing
	 */
	public void onCallRinging(Call call, Message resp, int code, String sdp)
	{
		printLog("onCallRinging()", LogLevel.LOW);
		if (call != this.call && call != call_transfer) 
		{
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("RINGING", LogLevel.HIGH);
		// Fix: on 183 we have to receive and play rtp data.
		// Launching the media application at this early moment fixes the issue
		// with eight seconds silence at the beginning of outgoing calls, too. NB
		if(code == 183) {
			if (user_profile.no_offer) {
				this.ring();
				return;
			}
			SessionDescriptor remote_sdp = new SessionDescriptor(sdp);
			sessionProduct(remote_sdp);
			if(!launchMediaApplication()) { // NB: hangup on audio initialization errors.
				printLog("error initializing audio. hanging up.", LogLevel.HIGH);
				call.hangup();
				changeStatus(UA_STATE_IDLE);				
			}
		}
		else if(code == 180)
			this.ring();
	}

	/** Callback function called when arriving a 2xx (call accepted) */
	public void onCallAccepted(Call call, String sdp, Message resp) 
	{
		printLog("onCallAccepted()", LogLevel.LOW);
		
		if (call != this.call && call != call_transfer) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		
		printLog("ACCEPTED/CALL", LogLevel.HIGH);
		
		if (!statusIs(UA_STATE_OUTGOING_CALL)) { // modified
			hangup();
			return;
		}
		changeStatus(UA_STATE_INCALL);
		
		SessionDescriptor remote_sdp = new SessionDescriptor(sdp);
		if (user_profile.no_offer) {
			// answer with the local sdp
			createAnswer(remote_sdp, useAudioCompression());
			call.ackWithAnswer(local_session);
		} else {
			// Update the local SDP along with offer/answer 
			sessionProduct(remote_sdp);
		}
		
		if(!launchMediaApplication()) { // NB: hangup on audio initialization errors.
			printLog("error initializing audio. hanging up.", LogLevel.HIGH);
			call.hangup();
			changeStatus(UA_STATE_IDLE);
			return;
		}

		if (call == call_transfer) 
		{
			StatusLine status_line = resp.getStatusLine();
			int code = status_line.getCode();
			String reason = status_line.getReason();
			this.call.notify(code, reason);
		}
	}

	/** Callback function called when arriving an ACK method (call confirmed) */
	public void onCallConfirmed(Call call, String sdp, Message ack) 
	{
		printLog("onCallConfirmed()", LogLevel.LOW);
	
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		
		printLog("CONFIRMED/CALL", LogLevel.HIGH);

		changeStatus(UA_STATE_INCALL);
		
		if (user_profile.hangup_time > 0)
		{
			this.automaticHangup(user_profile.hangup_time);
		}
	}

	/** Callback function called when arriving a 2xx (re-invite/modify accepted) */
	public void onCallReInviteAccepted(Call call, String sdp, Message resp) {
		printLog("onCallReInviteAccepted()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("RE-INVITE-ACCEPTED/CALL", LogLevel.HIGH);
		if (statusIs(UA_STATE_HOLD))
			changeStatus(UA_STATE_INCALL);
		else
			changeStatus(UA_STATE_HOLD);
	}

	/** Callback function called when arriving a 4xx (re-invite/modify failure) */
	public void onCallReInviteRefused(Call call, String reason, Message resp) {
		printLog("onCallReInviteRefused()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("RE-INVITE-REFUSED (" + reason + ")/CALL", LogLevel.HIGH);
	}

	/** Callback function called when arriving a 4xx (call failure) */
	public void onCallRefused(Call call, String reason, Message resp) {
		printLog("onCallRefused()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("REFUSED (" + reason + ")", LogLevel.HIGH);
		changeStatus(UA_STATE_IDLE);
		
		if (call == call_transfer) 
		{
			StatusLine status_line = resp.getStatusLine();
			int code = status_line.getCode();
			// String reason=status_line.getReason();
			this.call.notify(code, reason);
			call_transfer = null;
		}
	}

	/** Callback function called when arriving a 3xx (call redirection) */
	public void onCallRedirection(Call call, String reason,
			Vector<String> contact_list, Message resp) {
		printLog("onCallRedirection()", LogLevel.LOW);
		if (call != this.call) 
		{
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("REDIRECTION (" + reason + ")", LogLevel.HIGH);
		call.call(((String) contact_list.elementAt(0)));
	}

	/**
	 * Callback function that may be overloaded (extended). Called when arriving
	 * a CANCEL request
	 */
	public void onCallCanceling(Call call, Message cancel) {
		printLog("onCallCanceling()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("CANCEL", LogLevel.HIGH);
		if(cancel.hasHeader("Reason")) {
			org.zoolu.sip.header.Header head = cancel.getHeader("Reason");
			String val = head.getValue();
			if(val != null && val.contains("SIP; cause=200; text=\"Call completed elsewhere\"")) // NB: other local telephone picked the call.
				Receiver.ccCall.base = -1;
		}
		changeStatus(UA_STATE_IDLE);
	}

	/** Callback function called when arriving a BYE request */
	public void onCallClosing(Call call, Message bye) {
		printLog("onCallClosing()", LogLevel.LOW);
		if (call != this.call && call != call_transfer) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}

		if (call != call_transfer && call_transfer != null) {
			printLog("CLOSE PREVIOUS CALL", LogLevel.HIGH);
			this.call = call_transfer;
			call_transfer = null;
			return;
		}
		// else
		printLog("CLOSE", LogLevel.HIGH);
		closeMediaApplication();
		changeStatus(UA_STATE_IDLE);
	}

	/**
	 * Callback function called when arriving a response after a BYE request
	 * (call closed)
	 */
	public void onCallClosed(Call call, Message resp) {
		printLog("onCallClosed()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("CLOSE/OK", LogLevel.HIGH);
		
		changeStatus(UA_STATE_IDLE);
	}

	/** Callback function called when the invite expires */
	public void onCallTimeout(Call call) {
		printLog("onCallTimeout()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("NOT FOUND/TIMEOUT", LogLevel.HIGH);
		changeStatus(UA_STATE_IDLE);
		if (call == call_transfer) {
			int code = 408;
			String reason = "Request Timeout";
			this.call.notify(code, reason);
			call_transfer = null;
		}
	}

	// ****************** ExtendedCall callback functions ******************

	/**
	 * Callback function called when arriving a new REFER method (transfer
	 * request)
	 */
	public void onCallTransfer(ExtendedCall call, NameAddress refer_to,
			NameAddress refered_by, Message refer) {
		printLog("onCallTransfer()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("Transfer to " + refer_to.toString(), LogLevel.HIGH);
		call.acceptTransfer();
		call_transfer = new ExtendedCall(sip_provider, user_profile.from_url,
				user_profile.contact_url, this);
		call_transfer.call(refer_to.toString(), local_session);
	}

	/** Callback function called when a call transfer is accepted. */
	public void onCallTransferAccepted(ExtendedCall call, Message resp) {
		printLog("onCallTransferAccepted()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("Transfer accepted", LogLevel.HIGH);
	}

	/** Callback function called when a call transfer is refused. */
	public void onCallTransferRefused(ExtendedCall call, String reason,
			Message resp) {
		printLog("onCallTransferRefused()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("Transfer refused", LogLevel.HIGH);
	}

	/** Callback function called when a call transfer is successfully completed */
	public void onCallTransferSuccess(ExtendedCall call, Message notify) {
		printLog("onCallTransferSuccess()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("Transfer successed", LogLevel.HIGH);
		call.hangup();
	}

	/**
	 * Callback function called when a call transfer is NOT sucessfully
	 * completed
	 */
	public void onCallTransferFailure(ExtendedCall call, String reason,
			Message notify) {
		printLog("onCallTransferFailure()", LogLevel.LOW);
		if (call != this.call) {
			printLog("NOT the current call", LogLevel.LOW);
			return;
		}
		printLog("Transfer failed", LogLevel.HIGH);
	}

	// ************************* Schedule events ***********************

	/** Schedules a re-inviting event after <i>delay_time</i> secs. */
	void reInvite(final String contact_url, final int delay_time) {
		SessionDescriptor sdp = new SessionDescriptor(local_session);
		final SessionDescriptor new_sdp;
		if (statusIs(UserAgent.UA_STATE_INCALL)) { // modified
			new_sdp = new SessionDescriptor(
					sdp.getOrigin(), sdp.getSessionName(), new ConnectionField(
							"IP4", "0.0.0.0"), new TimeField());
		} else {
			new_sdp = new SessionDescriptor(
					sdp.getOrigin(), sdp.getSessionName(), new ConnectionField(
							"IP4", IpAddress.localIpAddress), new TimeField());
		}
		new_sdp.addMediaDescriptors(sdp.getMediaDescriptors());
		(new Thread() {
			public void run() {
				runReInvite(contact_url, new_sdp.toString(), delay_time);
			}
		}).start();
	}

	/** Re-invite. */
	private void runReInvite(String contact, String body, int delay_time) {
		try {
			if (delay_time > 0)
				Thread.sleep(delay_time * 1000);
		} catch (Exception e) {
			e.printStackTrace();
		}
			printLog("RE-INVITING/MODIFYING");
			if (call != null && call.isOnCall()) {
				printLog("REFER/TRANSFER");
				call.modify(contact, body);
			}
	}

	/** Schedules a call-transfer event after <i>delay_time</i> secs. */
	void callTransfer(final String transfer_to, final int delay_time) {
		(new Thread() {
			public void run() {
				runCallTransfer(transfer_to, delay_time);
			}
		}).start();
	}

	/** Call-transfer. */
	private void runCallTransfer(String transfer_to, int delay_time) {
		try {
			if (delay_time > 0)
				Thread.sleep(delay_time * 1000);
		} catch (Exception e) {
			e.printStackTrace();
		}
			if (call != null && call.isOnCall()) {
				printLog("REFER/TRANSFER");
				call.transfer(transfer_to);
			}
	}

	/** Schedules an automatic answer event after <i>delay_time</i> secs. */
	void automaticAccept(final int delay_time) {
		(new Thread() {
			public void run() {
				runAutomaticAccept(delay_time);
			}
		}).start();
	}

	/** Automatic answer. */
	private void runAutomaticAccept(int delay_time) {
		try {
			if (delay_time > 0)
				Thread.sleep(delay_time * 1000);
		} catch (Exception e) {
			e.printStackTrace();
		}
			if (call != null) {
				printLog("AUTOMATIC-ANSWER");
				accept();
			}
	}

	/** Schedules an automatic hangup event after <i>delay_time</i> secs. */
	void automaticHangup(final int delay_time) {
		(new Thread() {
			public void run() {
				runAutomaticHangup(delay_time);
			}
		}).start();
	}

	/** Automatic hangup. */
	private void runAutomaticHangup(int delay_time) {
		try {
			if (delay_time > 0)
				Thread.sleep(delay_time * 1000);
		} catch (Exception e) {
			e.printStackTrace();
		}
			if (call != null && call.isOnCall()) {
				printLog("AUTOMATIC-HANGUP");
				hangup();
			}

	}

	// ****************************** Logs *****************************

	/** Adds a new string to the default Log */
	void printLog(String str) {
		printLog(str, LogLevel.HIGH);
	}

	/** Adds a new string to the default Log */
	void printLog(String str, int level) {
		if (Sipdroid.release) return;
		if (log != null)
			log.println("UA: " + str, level + SipStack.LOG_LEVEL_UA);
		if ((user_profile == null || !user_profile.no_prompt)
				&& level <= LogLevel.HIGH)
			System.out.println("UA: " + str);
	}

	/** Adds the Exception message to the default Log */
	void printException(Exception e, int level) {
		if (Sipdroid.release) return;
		if (log != null)
			log.printException(e, level + SipStack.LOG_LEVEL_UA);
	}

}
