/* Copyright 2017 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.floodlight.pathverification;

import net.floodlightcontroller.packet.BasePacket;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.LLDPTLV;
import org.projectfloodlight.openflow.types.EthType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class VerificationPacket extends BasePacket {
    protected LLDPTLV chassisId;
    protected LLDPTLV portId;
    protected LLDPTLV ttl;
    protected List<LLDPTLV> optionalTLVList;
    protected EthType ethType;

    public VerificationPacket() {
        this.optionalTLVList = new ArrayList<LLDPTLV>();
    }

    public VerificationPacket(Data data) {
        this.optionalTLVList = new ArrayList<LLDPTLV>();
        deserialize(data.getData(), 0, data.getData().length);
    }

    /**
     * @return the chassisId
     */
    public LLDPTLV getChassisId() {
        return chassisId;
    }

    /**
     * @param chassisId the chassisId to set
     */
    public VerificationPacket setChassisId(LLDPTLV chassisId) {
        this.chassisId = chassisId;
        return this;
    }

    /**
     * @return the portId
     */
    public LLDPTLV getPortId() {
        return portId;
    }

    /**
     * @param portId the portId to set
     */
    public VerificationPacket setPortId(LLDPTLV portId) {
        this.portId = portId;
        return this;
    }

    /**
     * @return the ttl
     */
    public LLDPTLV getTtl() {
        return ttl;
    }

    /**
     * @param ttl the ttl to set
     */
    public VerificationPacket setTtl(LLDPTLV ttl) {
        this.ttl = ttl;
        return this;
    }

    /**
     * @return the optionalTLVList
     */
    public List<LLDPTLV> getOptionalTLVList() {
        return optionalTLVList;
    }

    /**
     * @param optionalTLVList the optionalTLVList to set
     */
    public VerificationPacket setOptionalTLVList(List<LLDPTLV> optionalTLVList) {
        this.optionalTLVList = optionalTLVList;
        return this;
    }

    public byte[] serialize() {
        int length = 2 + this.chassisId.getLength() + 2 + this.portId.getLength() +
                2 + this.ttl.getLength() + 2;
        for (LLDPTLV tlv : this.optionalTLVList) {
            if (tlv != null)
                length += 2 + tlv.getLength();
        }

        byte[] data = new byte[length];
        ByteBuffer bb = ByteBuffer.wrap(data);
        bb.put(this.chassisId.serialize());
        bb.put(this.portId.serialize());
        bb.put(this.ttl.serialize());
        for (LLDPTLV tlv : this.optionalTLVList) {
            if (tlv != null) bb.put(tlv.serialize());
        }
        bb.putShort((short) 0); // End of LLDPDU

        return data;
    }

    public IPacket deserialize(byte[] data, int offset, int length) {
        ByteBuffer bb = ByteBuffer.wrap(data, offset, length);
        LLDPTLV tlv;
        do {
            tlv = new LLDPTLV().deserialize(bb);

            // if there was a failure to deserialize stop processing TLVs
            if (tlv == null)
                break;
            switch (tlv.getType()) {
                case 0x0:
                    // can throw this one away, its just an end delimiter
                    break;
                case 0x1:
                    this.chassisId = tlv;
                    break;
                case 0x2:
                    this.portId = tlv;
                    break;
                case 0x3:
                    this.ttl = tlv;
                    break;
                default:
                    this.optionalTLVList.add(tlv);
                    break;
            }
        } while (tlv.getType() != 0 && bb.hasRemaining());
        return this;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 883;
        int result = super.hashCode();
        result = prime * result
                + ((chassisId == null) ? 0 : chassisId.hashCode());
        result = prime * result + (optionalTLVList.hashCode());
        result = prime * result + ((portId == null) ? 0 : portId.hashCode());
        result = prime * result + ((ttl == null) ? 0 : ttl.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (!(obj instanceof VerificationPacket))
            return false;
        VerificationPacket other = (VerificationPacket) obj;
        if (chassisId == null) {
            if (other.chassisId != null)
                return false;
        } else if (!chassisId.equals(other.chassisId))
            return false;
        if (!optionalTLVList.equals(other.optionalTLVList))
            return false;
        if (portId == null) {
            if (other.portId != null)
                return false;
        } else if (!portId.equals(other.portId))
            return false;
        if (ttl == null) {
            if (other.ttl != null)
                return false;
        } else if (!ttl.equals(other.ttl))
            return false;
        return true;
    }

    @Override
    public String toString() {
        String str = "";
        str += "chassisId=" + ((this.chassisId == null) ? "null" : this.chassisId.toString());
        str += " portId=" + ((this.portId == null) ? "null" : this.portId.toString());
        str += " ttl=" + ((this.ttl == null) ? "null" : this.ttl.toString());
        str += " etherType=" + ethType.toString();
        str += " optionalTlvList=[";
        if (this.optionalTLVList != null) {
            for (LLDPTLV l : optionalTLVList) str += l.toString() + ", ";
        }
        str += "]";
        return str;
    }
}
