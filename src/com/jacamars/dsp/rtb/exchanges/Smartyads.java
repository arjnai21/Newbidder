package com.jacamars.dsp.rtb.exchanges;

import java.io.InputStream;

import com.jacamars.dsp.rtb.pojo.BidRequest;

/**
 * A class to handle Smartyads ad exchange
 * @author Ben M. Faul
 *
 */

public class Smartyads extends BidRequest {
	
	public Smartyads() {
		super();
		parseSpecial();
	}
	
	/**
	 * Make a smartyads bid request using a String.
	 * @param in String. The JSON bid request for smartyads
	 * @throws Exception on JSON errors.
	 */
	public Smartyads(String  in) throws Exception  {
		super(in);
		parseSpecial();
    }	
	
	/**
	 * Make a smartyads bid request using an input stream.
	 * @param in InputStream. The contents of a HTTP post.
	 * @throws Exception on JSON errors.
	 */
	public Smartyads(InputStream in) throws Exception {
		super(in);
		parseSpecial();
	}
	
	/**
	 * Create a new Smartyads Exchange object from this class instance.
	 * @throws JsonProcessingException on parse errors.
	 * @throws Exception on stream reading errors
	 */
	@Override
	public Smartyads copy(InputStream in) throws Exception  {
		Smartyads copy =  new Smartyads(in);
		copy.usesEncodedAdm = usesEncodedAdm;
		copy.usesGzipResponse = usesGzipResponse;
		return copy;
	}
	
	
	/**
	 * Process special Smartyads stuff, sets the exchange name.
	 */
	@Override
	public boolean parseSpecial() {
		setExchange( "smartyads" );
		usesEncodedAdm = false;
		return true;
	}
}