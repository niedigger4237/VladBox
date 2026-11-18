package io.nekohasekai.sagernet.fmt.wireguard;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class WireGuardBean extends AbstractBean {

    public String localAddress;
    public String privateKey;
    public String peerPublicKey;
    public String peerPreSharedKey;
    public Integer mtu;
    public String reserved;

    // AmneziaWG (sing-box-extended). All empty / 0 => plain WireGuard.
    public Integer awgJc;
    public Integer awgJmin;
    public Integer awgJmax;
    public Integer awgS1;
    public Integer awgS2;
    public Integer awgS3;
    public Integer awgS4;
    public String awgH1;
    public String awgH2;
    public String awgH3;
    public String awgH4;
    public String awgI1;
    public String awgI2;
    public String awgI3;
    public String awgI4;
    public String awgI5;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (localAddress == null) localAddress = "";
        if (privateKey == null) privateKey = "";
        if (peerPublicKey == null) peerPublicKey = "";
        if (peerPreSharedKey == null) peerPreSharedKey = "";
        if (mtu == null) mtu = 1420;
        if (reserved == null) reserved = "";
        if (awgJc == null) awgJc = 0;
        if (awgJmin == null) awgJmin = 0;
        if (awgJmax == null) awgJmax = 0;
        if (awgS1 == null) awgS1 = 0;
        if (awgS2 == null) awgS2 = 0;
        if (awgS3 == null) awgS3 = 0;
        if (awgS4 == null) awgS4 = 0;
        if (awgH1 == null) awgH1 = "";
        if (awgH2 == null) awgH2 = "";
        if (awgH3 == null) awgH3 = "";
        if (awgH4 == null) awgH4 = "";
        if (awgI1 == null) awgI1 = "";
        if (awgI2 == null) awgI2 = "";
        if (awgI3 == null) awgI3 = "";
        if (awgI4 == null) awgI4 = "";
        if (awgI5 == null) awgI5 = "";
    }

    /** true when any AmneziaWG obfuscation parameter is set */
    public boolean isAmnezia() {
        return awgJc > 0 || awgJmin > 0 || awgJmax > 0
                || awgS1 > 0 || awgS2 > 0 || awgS3 > 0 || awgS4 > 0
                || !awgH1.isEmpty() || !awgH2.isEmpty() || !awgH3.isEmpty() || !awgH4.isEmpty()
                || !awgI1.isEmpty() || !awgI2.isEmpty() || !awgI3.isEmpty()
                || !awgI4.isEmpty() || !awgI5.isEmpty();
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(3);
        super.serialize(output);
        output.writeString(localAddress);
        output.writeString(privateKey);
        output.writeString(peerPublicKey);
        output.writeString(peerPreSharedKey);
        output.writeInt(mtu);
        output.writeString(reserved);
        // version 3: AmneziaWG
        output.writeInt(awgJc);
        output.writeInt(awgJmin);
        output.writeInt(awgJmax);
        output.writeInt(awgS1);
        output.writeInt(awgS2);
        output.writeInt(awgS3);
        output.writeInt(awgS4);
        output.writeString(awgH1);
        output.writeString(awgH2);
        output.writeString(awgH3);
        output.writeString(awgH4);
        output.writeString(awgI1);
        output.writeString(awgI2);
        output.writeString(awgI3);
        output.writeString(awgI4);
        output.writeString(awgI5);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        localAddress = input.readString();
        privateKey = input.readString();
        peerPublicKey = input.readString();
        peerPreSharedKey = input.readString();
        mtu = input.readInt();
        reserved = input.readString();
        if (version >= 3) {
            awgJc = input.readInt();
            awgJmin = input.readInt();
            awgJmax = input.readInt();
            awgS1 = input.readInt();
            awgS2 = input.readInt();
            awgS3 = input.readInt();
            awgS4 = input.readInt();
            awgH1 = input.readString();
            awgH2 = input.readString();
            awgH3 = input.readString();
            awgH4 = input.readString();
            awgI1 = input.readString();
            awgI2 = input.readString();
            awgI3 = input.readString();
            awgI4 = input.readString();
            awgI5 = input.readString();
        }
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public WireGuardBean clone() {
        return KryoConverters.deserialize(new WireGuardBean(), KryoConverters.serialize(this));
    }

    public static final Creator<WireGuardBean> CREATOR = new CREATOR<WireGuardBean>() {
        @NonNull
        @Override
        public WireGuardBean newInstance() {
            return new WireGuardBean();
        }

        @Override
        public WireGuardBean[] newArray(int size) {
            return new WireGuardBean[size];
        }
    };
}
