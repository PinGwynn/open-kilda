package org.openkilda.integration.model.response;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.io.Serializable;

/**
 * The Class Path.
 *
 * @author Gaurav Chugh
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"port_no", "segment_latency", "seq_id", "switch_id"})
public class PathNode implements Serializable {

    /** The port no. */
    @JsonProperty("port_no")
    private Integer portNo;

    /** The segment latency. */
    @JsonProperty("segment_latency")
    private Integer segmentLatency;

    /** The seq id. */
    @JsonProperty("seq_id")
    private Integer seqId;

    /** The switch id. */
    @JsonProperty("switch_id")
    private String switchId;

    /** The in port no. */
    @JsonProperty("in_port_no")
    private Integer inPortNo;

    /** The out port no. */
    @JsonProperty("out_port_no")
    private Integer outPortNo;

    
    @JsonCreator
    public PathNode(){
  	
    }
  
	@JsonCreator
	public PathNode(@JsonProperty("seq_id") Integer seqId, @JsonProperty("in_port_no") Integer inPortNo, @JsonProperty("out_port_no") Integer outPortNo,  @JsonProperty("switch_id") String switchId){
	  setSeqId(seqId);
	  setInPortNo(inPortNo);
	  setOutPortNo(outPortNo);
	  setSwitchId(switchId);
	}
    /** The Constant serialVersionUID. */
    private final static long serialVersionUID = -4515006227265225751L;

    /**
     * Gets the port no.
     *
     * @return the port no
     */
    public Integer getPortNo() {
        return portNo;
    }

    /**
     * Sets the port no.
     *
     * @param portNo the new port no
     */
    public void setPortNo(final Integer portNo) {
        this.portNo = portNo;
    }

    /**
     * Gets the segment latency.
     *
     * @return the segment latency
     */
    public Integer getSegmentLatency() {
        return segmentLatency;
    }

    /**
     * Sets the segment latency.
     *
     * @param segmentLatency the new segment latency
     */
    public void setSegmentLatency(final Integer segmentLatency) {
        this.segmentLatency = segmentLatency;
    }

    /**
     * Gets the seq id.
     *
     * @return the seq id
     */
    public Integer getSeqId() {
        return seqId;
    }

    /**
     * Sets the seq id.
     *
     * @param seqId the new seq id
     */
    public void setSeqId(final Integer seqId) {
        this.seqId = seqId;
    }

    /**
     * Gets the switch id.
     *
     * @return the switch id
     */
    public String getSwitchId() {
        return switchId;
    }

    /**
     * Sets the switch id.
     *
     * @param switchId the new switch id
     */
    public void setSwitchId(final String switchId) {
        this.switchId = switchId;
    }

    /**
     * Gets the in port no.
     *
     * @return the in port no
     */
    public Integer getInPortNo() {
        return inPortNo;
    }

    /**
     * Sets the in port no.
     *
     * @param inPortNo the new in port no
     */
    public void setInPortNo(final Integer inPortNo) {
        this.inPortNo = inPortNo;
    }

    /**
     * Gets the out port no.
     *
     * @return the out port no
     */
    public Integer getOutPortNo() {
        return outPortNo;
    }

    /**
     * Sets the out port no.
     *
     * @param outPortNo the new out port no
     */
    public void setOutPortNo(final Integer outPortNo) {
        this.outPortNo = outPortNo;
    }

	@Override
	public String toString() {
		return "PathNode [portNo=" + portNo + ", segmentLatency="
				+ segmentLatency + ", seqId=" + seqId + ", switchId="
				+ switchId + ", inPortNo=" + inPortNo + ", outPortNo="
				+ outPortNo + "]";
	}
    
    

}
