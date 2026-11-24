package moe.matsuri.nb4a.proxy.anytls;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class AnyTLSBean extends AbstractBean {

    public static final Creator<AnyTLSBean> CREATOR = new CREATOR<AnyTLSBean>() {
        @NonNull
        @Override
        public AnyTLSBean newInstance() {
            return new AnyTLSBean();
        }

        @Override
        public AnyTLSBean[] newArray(int size) {
            return new AnyTLSBean[size];
        }
    };
    public String password;
    public String sni;
    public String alpn;
    public String certificates;
    public String utlsFingerprint;
    public Boolean allowInsecure;
    // In sing-box, this seemed can be used with REALITY.
    // But even mihomo appended many options, it still not provide REALITY.
    // https://github.com/anytls/anytls-go/blob/4636d90462fa21a510420512d7706a9acf69c7b9/docs/faq.md?plain=1#L25-L37

    public String echConfig;
    public String idleSessionCheckInterval;
    public String idleSessionTimeout;
    public String minIdleSession;
    public String clientMetadata;
    public String outboundDialer;
    public String tlsExtras;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (password == null) password = "";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (certificates == null) certificates = "";
        if (utlsFingerprint == null) utlsFingerprint = "";
        if (allowInsecure == null) allowInsecure = false;
        if (echConfig == null) echConfig = "";
        if (idleSessionCheckInterval == null) idleSessionCheckInterval = "";
        if (idleSessionTimeout == null) idleSessionTimeout = "";
        if (minIdleSession == null) minIdleSession = "";
        if (clientMetadata == null) clientMetadata = "";
        if (outboundDialer == null) outboundDialer = "";
        if (tlsExtras == null) tlsExtras = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(3);
        super.serialize(output);
        output.writeString(password);
        output.writeString(sni);
        output.writeString(alpn);
        output.writeString(certificates);
        output.writeString(utlsFingerprint);
        output.writeBoolean(allowInsecure);
        output.writeString(echConfig);
        output.writeString(idleSessionCheckInterval);
        output.writeString(idleSessionTimeout);
        output.writeString(minIdleSession);
        output.writeString(clientMetadata);
        output.writeString(outboundDialer);
        output.writeString(tlsExtras);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        password = input.readString();
        sni = input.readString();
        alpn = input.readString();
        certificates = input.readString();
        utlsFingerprint = input.readString();
        allowInsecure = input.readBoolean();
        echConfig = input.readString();
        if (version >= 1) {
            idleSessionCheckInterval = input.readString();
            idleSessionTimeout = input.readString();
            minIdleSession = input.readString();
            clientMetadata = input.readString();
        }
        if (version >= 2) outboundDialer = input.readString();
        if (version >= 3) tlsExtras = input.readString();
    }

    @NotNull
    @Override
    public AnyTLSBean clone() {
        return KryoConverters.deserialize(new AnyTLSBean(), KryoConverters.serialize(this));
    }
}