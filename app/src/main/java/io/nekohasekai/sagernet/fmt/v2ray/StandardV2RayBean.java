package io.nekohasekai.sagernet.fmt.v2ray;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean;
import moe.matsuri.nb4a.utils.JavaUtil;

public abstract class StandardV2RayBean extends AbstractBean {

    public String uuid;
    public String encryption; // or VLESS flow

    //////// End of VMess & VLESS ////////

    // "V2Ray Transport" tcp/http/ws/quic/grpc/httpupgrade
    public String type;

    public String host;

    public String path;

    // xhttp (sing-box-extended): mode auto/packet-up/stream-up/stream-one + Xray style "extra" JSON
    public String xhttpMode;
    public String xhttpExtra;

    // --------------------------------------- tls?

    public String security;

    public String sni;

    public String alpn;

    public String utlsFingerprint;

    public Boolean allowInsecure;

    // --------------------------------------- reality


    public String realityPubKey;

    public String realityShortId;


    // --------------------------------------- //

    public Integer wsMaxEarlyData;
    public String earlyDataHeaderName;

    public String certificates;

    // --------------------------------------- ech

    public Boolean enableECH;

    public String echConfig;

    // --------------------------------------- Mux

    public Boolean enableMux;
    public Boolean muxPadding;
    public Integer muxType;
    public Integer muxConcurrency;
    public Integer muxMaxConnections;
    public Integer muxMinStreams;
    public Boolean muxBrutal;
    public Integer muxBrutalUp;
    public Integer muxBrutalDown;

    // --------------------------------------- Throne parity (version 5)

    public Boolean tlsDisableSni;
    public String tlsPubKeySha256;
    public String tlsCipherSuites;
    public Boolean tlsFragment;
    public String tlsFragmentFallbackDelay;
    public Boolean tlsRecordFragment;
    public String transportHeaders; // "Name: value" per line
    public String httpMethod;
    public Integer grpcIdleTimeout;
    public Integer grpcPingTimeout;
    public Boolean globalPadding; // VMess
    public Boolean authenticatedLength; // VMess
    public String tlsMinVersion;
    public String tlsMaxVersion;
    public String tlsCurves;
    public String tlsHandshakeTimeout;
    public String tlsClientCertificate;
    public String tlsClientKey;
    public String echQueryName;
    public Boolean realityHybrid;
    public String realityHybridMode;
    public String vlessEncryption;
    public String vlessNetwork;
    public String kcpMtu;
    public String kcpTti;
    public String kcpUp;
    public String kcpDown;
    public String kcpRead;
    public String kcpWrite;
    public String kcpHeader;
    public String kcpSeed;
    public Boolean kcpCongestion;
    public String tlsEngine;
    public String tlsCertificatePath;
    public String tlsClientCertificatePath;
    public String tlsClientKeyPath;
    public String echConfigPath;
    public String tlsSpoof;
    public String tlsSpoofMethod;
    public Boolean tlsKernelTx;
    public Boolean tlsKernelRx;
    public String outboundDialer;
    public String httpIdleTimeout;
    public String httpPingTimeout;
    public Boolean grpcPermitWithoutStream;


    // --------------------------------------- //

    public Integer packetEncoding; // 1:packet 2:xudp

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (JavaUtil.isNullOrBlank(uuid)) uuid = "";

        if (JavaUtil.isNullOrBlank(type)) type = "tcp";
        else if ("h2".equals(type)) type = "http";

        type = type.toLowerCase();

        if (JavaUtil.isNullOrBlank(host)) host = "";
        if (JavaUtil.isNullOrBlank(path)) path = "";
        if (JavaUtil.isNullOrBlank(xhttpMode)) xhttpMode = "auto";
        if (xhttpExtra == null) xhttpExtra = "";

        if (JavaUtil.isNullOrBlank(security)) {
            if (this instanceof TrojanBean) {
                security = "tls";
            } else {
                security = "none";
            }
        }
        if (JavaUtil.isNullOrBlank(sni)) sni = "";
        if (JavaUtil.isNullOrBlank(alpn)) alpn = "";

        if (JavaUtil.isNullOrBlank(certificates)) certificates = "";
        if (JavaUtil.isNullOrBlank(earlyDataHeaderName)) earlyDataHeaderName = "";
        if (JavaUtil.isNullOrBlank(utlsFingerprint)) utlsFingerprint = "";

        if (wsMaxEarlyData == null) wsMaxEarlyData = 0;
        if (allowInsecure == null) allowInsecure = false;
        if (packetEncoding == null) packetEncoding = 0;

        if (realityPubKey == null) realityPubKey = "";
        if (realityShortId == null) realityShortId = "";

        if (enableECH == null) enableECH = false;
        if (JavaUtil.isNullOrBlank(echConfig)) echConfig = "";

        if (enableMux == null) enableMux = false;
        if (muxPadding == null) muxPadding = false;
        if (muxType == null) muxType = 0;
        if (muxConcurrency == null) muxConcurrency = 1;
        if (muxMaxConnections == null) muxMaxConnections = 0;
        if (muxMinStreams == null) muxMinStreams = 0;
        if (muxBrutal == null) muxBrutal = false;
        if (muxBrutalUp == null) muxBrutalUp = 0;
        if (muxBrutalDown == null) muxBrutalDown = 0;

        if (tlsDisableSni == null) tlsDisableSni = false;
        if (tlsPubKeySha256 == null) tlsPubKeySha256 = "";
        if (tlsCipherSuites == null) tlsCipherSuites = "";
        if (tlsFragment == null) tlsFragment = false;
        if (tlsFragmentFallbackDelay == null) tlsFragmentFallbackDelay = "";
        if (tlsRecordFragment == null) tlsRecordFragment = false;
        if (transportHeaders == null) transportHeaders = "";
        if (httpMethod == null) httpMethod = "";
        if (grpcIdleTimeout == null) grpcIdleTimeout = 0;
        if (grpcPingTimeout == null) grpcPingTimeout = 0;
        if (globalPadding == null) globalPadding = false;
        if (authenticatedLength == null) authenticatedLength = false;
        if (tlsMinVersion == null) tlsMinVersion = "";
        if (tlsMaxVersion == null) tlsMaxVersion = "";
        if (tlsCurves == null) tlsCurves = "";
        if (tlsHandshakeTimeout == null) tlsHandshakeTimeout = "";
        if (tlsClientCertificate == null) tlsClientCertificate = "";
        if (tlsClientKey == null) tlsClientKey = "";
        if (echQueryName == null) echQueryName = "";
        if (!"auto".equals(realityHybridMode) && !"enabled".equals(realityHybridMode) && !"disabled".equals(realityHybridMode)) {
            realityHybridMode = realityHybrid == null ? "auto" : (realityHybrid ? "enabled" : "disabled");
        }
        realityHybrid = !"disabled".equals(realityHybridMode);
        if (vlessEncryption == null) vlessEncryption = "";
        if (vlessNetwork == null) vlessNetwork = "";
        if (kcpMtu == null) kcpMtu = "";
        if (kcpTti == null) kcpTti = "";
        if (kcpUp == null) kcpUp = "";
        if (kcpDown == null) kcpDown = "";
        if (kcpRead == null) kcpRead = "";
        if (kcpWrite == null) kcpWrite = "";
        if (kcpHeader == null) kcpHeader = "";
        if (kcpSeed == null) kcpSeed = "";
        if (kcpCongestion == null) kcpCongestion = false;
        if (tlsEngine == null) tlsEngine = "";
        if (tlsCertificatePath == null) tlsCertificatePath = "";
        if (tlsClientCertificatePath == null) tlsClientCertificatePath = "";
        if (tlsClientKeyPath == null) tlsClientKeyPath = "";
        if (echConfigPath == null) echConfigPath = "";
        if (tlsSpoof == null) tlsSpoof = "";
        if (tlsSpoofMethod == null) tlsSpoofMethod = "";
        if (tlsKernelTx == null) tlsKernelTx = false;
        if (tlsKernelRx == null) tlsKernelRx = false;
        if (outboundDialer == null) outboundDialer = "";
        if (httpIdleTimeout == null) httpIdleTimeout = "";
        if (httpPingTimeout == null) httpPingTimeout = "";
        if (grpcPermitWithoutStream == null) grpcPermitWithoutStream = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(13);
        super.serialize(output);
        output.writeString(uuid);
        output.writeString(encryption);
        if (this instanceof VMessBean) {
            output.writeInt(((VMessBean) this).alterId);
        }

        output.writeString(type);
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                output.writeString(host);
                output.writeString(path);
                output.writeInt(wsMaxEarlyData);
                output.writeString(earlyDataHeaderName);
                break;
            }
            case "http":
            case "httpupgrade": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
            case "xhttp": {
                output.writeString(host);
                output.writeString(path);
                output.writeString(xhttpMode);
                output.writeString(xhttpExtra);
                break;
            }
            case "grpc": {
                output.writeString(path);
                break;
            }
        }

        output.writeString(security);
        if ("tls".equals(security)) {
            output.writeString(sni);
            output.writeString(alpn);
            output.writeString(certificates);
            output.writeBoolean(allowInsecure);
            output.writeString(utlsFingerprint);
            output.writeString(realityPubKey);
            output.writeString(realityShortId);
        }

        output.writeBoolean(enableECH);
        output.writeString(echConfig);

        output.writeInt(packetEncoding);

        output.writeBoolean(enableMux);
        output.writeBoolean(muxPadding);
        output.writeInt(muxType);
        output.writeInt(muxConcurrency);

        output.writeInt(muxMaxConnections);
        output.writeInt(muxMinStreams);
        output.writeBoolean(muxBrutal);
        output.writeInt(muxBrutalUp);
        output.writeInt(muxBrutalDown);
        output.writeBoolean(tlsDisableSni);
        output.writeString(tlsPubKeySha256);
        output.writeString(tlsCipherSuites);
        output.writeBoolean(tlsFragment);
        output.writeString(tlsFragmentFallbackDelay);
        output.writeBoolean(tlsRecordFragment);
        output.writeString(transportHeaders);
        output.writeString(httpMethod);
        output.writeInt(grpcIdleTimeout);
        output.writeInt(grpcPingTimeout);
        output.writeBoolean(globalPadding);
        output.writeBoolean(authenticatedLength);
        output.writeString(tlsMinVersion);
        output.writeString(tlsMaxVersion);
        output.writeString(tlsCurves);
        output.writeString(tlsHandshakeTimeout);
        output.writeString(tlsClientCertificate);
        output.writeString(tlsClientKey);
        output.writeString(echQueryName);
        output.writeBoolean(!"disabled".equals(effectiveRealityHybridMode()));
        output.writeString(vlessEncryption);
        output.writeString(vlessNetwork);
        output.writeString(kcpMtu);
        output.writeString(kcpTti);
        output.writeString(kcpUp);
        output.writeString(kcpDown);
        output.writeString(kcpRead);
        output.writeString(kcpWrite);
        output.writeString(kcpHeader);
        output.writeString(kcpSeed);
        output.writeBoolean(kcpCongestion);
        output.writeString(tlsEngine);
        output.writeString(tlsCertificatePath);
        output.writeString(tlsClientCertificatePath);
        output.writeString(tlsClientKeyPath);
        output.writeString(echConfigPath);
        output.writeString(tlsSpoof);
        output.writeString(tlsSpoofMethod);
        output.writeBoolean(tlsKernelTx);
        output.writeBoolean(tlsKernelRx);
        output.writeString(outboundDialer);
        output.writeString(httpIdleTimeout);
        output.writeString(httpPingTimeout);
        output.writeBoolean(grpcPermitWithoutStream);
        output.writeString(effectiveRealityHybridMode());
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        uuid = input.readString();
        encryption = input.readString();
        if (this instanceof VMessBean) {
            ((VMessBean) this).alterId = input.readInt();
        }

        type = input.readString();
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                host = input.readString();
                path = input.readString();
                wsMaxEarlyData = input.readInt();
                earlyDataHeaderName = input.readString();
                break;
            }
            case "http":
            case "httpupgrade": {
                host = input.readString();
                path = input.readString();
                break;
            }
            case "xhttp": {
                host = input.readString();
                path = input.readString();
                xhttpMode = input.readString();
                xhttpExtra = input.readString();
                break;
            }
            case "grpc": {
                path = input.readString();
                if (version < 4) {
                    // 解决老版本数据的读取问题
                    input.readString();
                    input.readString();
                }
                break;
            }
        }

        security = input.readString();
        if ("tls".equals(security)) {
            sni = input.readString();
            alpn = input.readString();
            certificates = input.readString();
            allowInsecure = input.readBoolean();
            utlsFingerprint = input.readString();
            realityPubKey = input.readString();
            realityShortId = input.readString();
        }

        if (version >= 1) {
            enableECH = input.readBoolean();
            if (version >= 3) {
                echConfig = input.readString();
            } else {
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            }
        } else if (version == 0) {
            // 从老版本升级上来但是 version == 0, 可能有 enableECH 也可能没有，需要做判断
            int position = input.getByteBuffer().position(); // 当前位置

            boolean tmpEnableECH = input.readBoolean();
            int tmpPacketEncoding = input.readInt();

            input.setPosition(position); // 读后归位

            if (tmpPacketEncoding != 1 && tmpPacketEncoding != 2) {
                enableECH = tmpEnableECH;
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            } // 否则后一位就是 packetEncoding
        }

        packetEncoding = input.readInt();

        if (version >= 2) {
            enableMux = input.readBoolean();
            muxPadding = input.readBoolean();
            muxType = input.readInt();
            muxConcurrency = input.readInt();
        }

        if (version >= 5) {
            muxMaxConnections = input.readInt();
            muxMinStreams = input.readInt();
            muxBrutal = input.readBoolean();
            muxBrutalUp = input.readInt();
            muxBrutalDown = input.readInt();
            tlsDisableSni = input.readBoolean();
            tlsPubKeySha256 = input.readString();
            tlsCipherSuites = input.readString();
            tlsFragment = input.readBoolean();
            tlsFragmentFallbackDelay = input.readString();
            tlsRecordFragment = input.readBoolean();
            transportHeaders = input.readString();
            httpMethod = input.readString();
            grpcIdleTimeout = input.readInt();
            grpcPingTimeout = input.readInt();
            globalPadding = input.readBoolean();
            authenticatedLength = input.readBoolean();
        }
        if (version >= 6) {
            tlsMinVersion = input.readString();
            tlsMaxVersion = input.readString();
            tlsCurves = input.readString();
            tlsHandshakeTimeout = input.readString();
            tlsClientCertificate = input.readString();
            tlsClientKey = input.readString();
            echQueryName = input.readString();
            realityHybrid = input.readBoolean();
        }
        if (version >= 7) {
            vlessEncryption = input.readString();
            vlessNetwork = input.readString();
        }
        if (version >= 8) {
            kcpMtu = input.readString();
            kcpTti = input.readString();
            kcpUp = input.readString();
            kcpDown = input.readString();
            kcpRead = input.readString();
            kcpWrite = input.readString();
            kcpHeader = input.readString();
            kcpSeed = input.readString();
            kcpCongestion = input.readBoolean();
        }
        if (version >= 9) {
            tlsEngine = input.readString();
            tlsCertificatePath = input.readString();
            tlsClientCertificatePath = input.readString();
            tlsClientKeyPath = input.readString();
            echConfigPath = input.readString();
        }
        if (version >= 10) {
            tlsSpoof = input.readString();
            tlsSpoofMethod = input.readString();
            tlsKernelTx = input.readBoolean();
            tlsKernelRx = input.readBoolean();
        }
        if (version >= 11) outboundDialer = input.readString();
        if (version >= 12) {
            httpIdleTimeout = input.readString();
            httpPingTimeout = input.readString();
            grpcPermitWithoutStream = input.readBoolean();
        }
        if (version >= 13) realityHybridMode = input.readString();
        else realityHybridMode = Boolean.TRUE.equals(realityHybrid) ? "enabled" : "disabled";
    }

    public String effectiveRealityHybridMode() {
        if ("auto".equals(realityHybridMode) || "enabled".equals(realityHybridMode) || "disabled".equals(realityHybridMode)) return realityHybridMode;
        return realityHybrid == null ? "auto" : (realityHybrid ? "enabled" : "disabled");
    }

    public boolean isVLESS() {
        if (this instanceof VMessBean) {
            Integer aid = ((VMessBean) this).alterId;
            return aid != null && aid == -1;
        }
        return false;
    }

}
