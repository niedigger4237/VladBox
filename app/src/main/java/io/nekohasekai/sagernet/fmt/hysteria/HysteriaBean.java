package io.nekohasekai.sagernet.fmt.hysteria;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import io.nekohasekai.sagernet.ktx.NetsKt;
import kotlin.text.StringsKt;

public class HysteriaBean extends AbstractBean {
    public Integer protocolVersion;

    // Use serverPorts instead of serverPort
    public String serverPorts;

    // HY1 & 2

    public String authPayload;
    public String obfuscation;
    public String sni;
    public String caText;
    public Integer uploadMbps;
    public Integer downloadMbps;
    public Boolean allowInsecure;
    public Integer streamReceiveWindow;
    public Integer connectionReceiveWindow;
    public Boolean disableMtuDiscovery;
    public Integer hopInterval;
    public String hy2ObfsType;
    public String hy2BbrProfile;
    public String hy2HopIntervalMax;
    public String hy2GeckoMin;
    public String hy2GeckoMax;
    public String hy2Network;
    public String hy2IdleTimeout;
    public String hy2KeepAlive;
    public String hy2StreamWindow;
    public String hy2ConnectionWindow;
    public String hy2MaxStreams;
    public String hy2InitialPacket;
    public String hy2Realm;
    public Boolean hy2BrutalDebug;
    public Boolean hy2DisableChrome;
    public Boolean hy2DisablePathMtu;
    public String outboundDialer;
    public String quicTlsOptions;

    // HY1

    public String alpn;

    public static final int TYPE_NONE = 0;
    public static final int TYPE_STRING = 1;
    public static final int TYPE_BASE64 = 2;
    public Integer authPayloadType;

    public static final int PROTOCOL_UDP = 0;
    public static final int PROTOCOL_FAKETCP = 1;
    public static final int PROTOCOL_WECHAT_VIDEO = 2;
    public Integer protocol;

    @Override
    public boolean canMapping() {
        return protocol != PROTOCOL_FAKETCP;
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (protocolVersion == null) protocolVersion = 2;

        if (authPayloadType == null) authPayloadType = TYPE_NONE;
        if (authPayload == null) authPayload = "";
        if (protocol == null) protocol = PROTOCOL_UDP;
        if (obfuscation == null) obfuscation = "";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (caText == null) caText = "";
        if (allowInsecure == null) allowInsecure = false;

        if (protocolVersion == 1) {
            if (uploadMbps == null) uploadMbps = 10;
            if (downloadMbps == null) downloadMbps = 50;
        } else {
            if (uploadMbps == null) uploadMbps = 0;
            if (downloadMbps == null) downloadMbps = 0;
        }

        if (streamReceiveWindow == null) streamReceiveWindow = 0;
        if (connectionReceiveWindow == null) connectionReceiveWindow = 0;
        if (disableMtuDiscovery == null) disableMtuDiscovery = false;
        if (hopInterval == null) hopInterval = 10;
        if (serverPorts == null) serverPorts = "443";
        if (hy2ObfsType == null) hy2ObfsType = "salamander";
        if (hy2BbrProfile == null) hy2BbrProfile = "";
        if (hy2HopIntervalMax == null) hy2HopIntervalMax = "";
        if (hy2GeckoMin == null) hy2GeckoMin = "";
        if (hy2GeckoMax == null) hy2GeckoMax = "";
        if (hy2Network == null) hy2Network = "";
        if (hy2IdleTimeout == null) hy2IdleTimeout = "";
        if (hy2KeepAlive == null) hy2KeepAlive = "";
        if (hy2StreamWindow == null) hy2StreamWindow = "";
        if (hy2ConnectionWindow == null) hy2ConnectionWindow = "";
        if (hy2MaxStreams == null) hy2MaxStreams = "";
        if (hy2InitialPacket == null) hy2InitialPacket = "";
        if (hy2Realm == null) hy2Realm = "";
        if (hy2BrutalDebug == null) hy2BrutalDebug = false;
        if (hy2DisableChrome == null) hy2DisableChrome = false;
        if (hy2DisablePathMtu == null) hy2DisablePathMtu = false;
        if (outboundDialer == null) outboundDialer = "";
        if (quicTlsOptions == null) quicTlsOptions = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(10);
        super.serialize(output);

        output.writeInt(protocolVersion);

        output.writeInt(authPayloadType);
        output.writeString(authPayload);
        output.writeInt(protocol);
        output.writeString(obfuscation);
        output.writeString(sni);
        output.writeString(alpn);

        output.writeInt(uploadMbps);
        output.writeInt(downloadMbps);
        output.writeBoolean(allowInsecure);

        output.writeString(caText);
        output.writeInt(streamReceiveWindow);
        output.writeInt(connectionReceiveWindow);
        output.writeBoolean(disableMtuDiscovery);
        output.writeInt(hopInterval);
        output.writeString(serverPorts);
        output.writeString(hy2ObfsType);
        output.writeString(hy2BbrProfile);
        output.writeString(hy2HopIntervalMax);
        output.writeString(hy2GeckoMin);
        output.writeString(hy2GeckoMax);
        output.writeString(hy2Network);
        output.writeString(hy2IdleTimeout);
        output.writeString(hy2KeepAlive);
        output.writeString(hy2StreamWindow);
        output.writeString(hy2ConnectionWindow);
        output.writeString(hy2MaxStreams);
        output.writeString(hy2InitialPacket);
        output.writeString(hy2Realm);
        output.writeBoolean(hy2BrutalDebug);
        output.writeBoolean(hy2DisableChrome);
        output.writeBoolean(hy2DisablePathMtu);
        output.writeString(outboundDialer);
        output.writeString(quicTlsOptions);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        if (version >= 7) {
            protocolVersion = input.readInt();
        } else {
            protocolVersion = 1;
        }
        authPayloadType = input.readInt();
        authPayload = input.readString();
        if (version >= 3) {
            protocol = input.readInt();
        }
        obfuscation = input.readString();
        sni = input.readString();
        if (version >= 2) {
            alpn = input.readString();
        }
        uploadMbps = input.readInt();
        downloadMbps = input.readInt();
        allowInsecure = input.readBoolean();
        if (version >= 1) {
            caText = input.readString();
            streamReceiveWindow = input.readInt();
            connectionReceiveWindow = input.readInt();
            if (version != 4) disableMtuDiscovery = input.readBoolean(); // note: skip 4
        }
        if (version >= 5) {
            hopInterval = input.readInt();
        }
        if (version >= 6) {
            serverPorts = input.readString();
        } else {
            // old update to new
            if (HysteriaFmtKt.isMultiPort(serverAddress)) {
                serverPorts = StringsKt.substringAfterLast(serverAddress, ":", serverAddress);
                serverAddress = StringsKt.substringBeforeLast(serverAddress, ":", serverAddress);
            } else {
                serverPorts = serverPort.toString();
            }
        }
        if (version >= 8) {
            hy2ObfsType = input.readString();
            hy2BbrProfile = input.readString();
            hy2HopIntervalMax = input.readString();
            hy2GeckoMin = input.readString();
            hy2GeckoMax = input.readString();
        }
        if (version >= 9) {
            hy2Network = input.readString();
            hy2IdleTimeout = input.readString();
            hy2KeepAlive = input.readString();
            hy2StreamWindow = input.readString();
            hy2ConnectionWindow = input.readString();
            hy2MaxStreams = input.readString();
            hy2InitialPacket = input.readString();
            hy2Realm = input.readString();
            hy2BrutalDebug = input.readBoolean();
            hy2DisableChrome = input.readBoolean();
            hy2DisablePathMtu = input.readBoolean();
        }
        if (version >= 10) {
            outboundDialer = input.readString();
            quicTlsOptions = input.readString();
        }
    }

    @Override
    public String displayAddress() {
        return NetsKt.wrapIPV6Host(serverAddress) + ":" + serverPorts;
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public HysteriaBean clone() {
        return KryoConverters.deserialize(new HysteriaBean(), KryoConverters.serialize(this));
    }

    public static final Creator<HysteriaBean> CREATOR = new CREATOR<HysteriaBean>() {
        @NonNull
        @Override
        public HysteriaBean newInstance() {
            return new HysteriaBean();
        }

        @Override
        public HysteriaBean[] newArray(int size) {
            return new HysteriaBean[size];
        }
    };
}
