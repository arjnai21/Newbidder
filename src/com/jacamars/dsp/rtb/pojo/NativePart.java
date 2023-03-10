package com.jacamars.dsp.rtb.pojo;

import java.util.ArrayList;
import java.util.List;

import com.hazelcast.internal.json.JsonObject;
import com.jacamars.dsp.rtb.nativeads.creative.Data;
import com.jacamars.dsp.rtb.nativeads.creative.Img;
import com.jacamars.dsp.rtb.nativeads.creative.NativeVideo;
import com.jacamars.dsp.rtb.nativeads.creative.Title;

/**
 * A class that encapsulates native content bid request. Note, this is defined and filled out in the construction of
 * the bid request, and is used to match against creatives.
 * @author Ben M. Faul
 *
 */
public class NativePart {

	/** The layout type as specified in the bid request. -1 means the bid request doesn't specify one */
	public int layout = -1;
	/** The title asset as defined in the bid request */
	public Title title;
	/** The image asset as defined in the bid request */
	public Img img;
	/** The video asset as defined in the bid request */
	public NativeVideo video;
	/** The data assets, as defined in the bid request */
	public List<Data>data = new ArrayList<Data>();
	
	/** 
	 * An empty constructor 
	 */
	public NativePart() {
		
	}
	
	public NativePart(JsonObject node) {
		
		
	}
}
