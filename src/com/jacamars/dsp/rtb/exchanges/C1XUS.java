package com.jacamars.dsp.rtb.exchanges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jacamars.dsp.rtb.blocks.LookingGlass;
import com.jacamars.dsp.rtb.pojo.BidRequest;
import com.jacamars.dsp.rtb.tools.IsoTwo2Iso3;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * A class to handle C1X ad exchange
 * @author Ben M. Faul
 *
 */

public class C1XUS extends BidRequest {

		// Reference to symbol that
		private static final IsoTwo2Iso3 isoMap = (IsoTwo2Iso3)LookingGlass.symbols.get("@ISO2-3");
		private static Map<String,TextNode> cache = new HashMap<String,TextNode>();
        public C1XUS() {
                super();
                parseSpecial();
        }

        /**
         * Make a C1X bid request using a String.
         * @param in String. The JSON bid request for Epom
         * @throws Exception on JSON errors.
         */
        public C1XUS(String  in) throws Exception  {
                super(in);
                parseSpecial();
        }

        /**
         * Make a C1X bid request using an input stream.
         * @param in InputStream. The contents of a HTTP post.
         * @throws Exception on JSON errors.
         */
        public C1XUS(InputStream in) throws Exception {
                super(in);
                parseSpecial();
        }

        /**
         * Create a c1x bid request from a string builder buffer
         * @param in StringBuilder. The text.
         * @throws Exception on parsing errors.
         */
        public C1XUS(StringBuilder in) throws Exception {
			super(in);
			parseSpecial();
		}

		/**
         * Process special C1X stuff, sets the exchange name. Sets encoding.
         */
        @Override
        public boolean parseSpecial() {
               setExchange( "c1xus" );
                usesEncodedAdm = false;

			if (rootNode == null)       // can happen on initialization of the template class
				return false;
                
                // C1x can have protocol marker in the domain, need to strip it
                if (siteDomain != null) {
                	siteDomain = siteDomain.replaceAll("http://", "");
                	siteDomain = siteDomain.replaceAll("https://", "");
                	JsonNode node = rootNode.get("site");
                	if (node == null)
                		node = rootNode.get("app");
                	if (node.has("domain")) {
                		ObjectNode n = (ObjectNode)node;
                		n.remove("domain");
                		n.put("domain", siteDomain);
                	}
                }
                
                // C1X uses ISO2 country codes, we can't digest that with our campaign processor, so we
                // will convert it for them and patch the bid request.
                // Use a cache of country codes to keep from creating a lot of objects to be later garbage collected.

                
                
                
                Object o = this.database.get("device.geo.country");
                if (o instanceof MissingNode) {
                	return true;
                }
                
                TextNode country = (TextNode)o;
                TextNode test = null;
                if (country != null) {
                	test = cache.get(country.asText());
                	if (test == null) {
                		String iso3 = isoMap.query(country.asText());
                		test = new TextNode(iso3);
                	}
                	if (test != country)
                		database.put("device.geo.country", test);
                }
                return true;
        }
        
    	/**
    	 * Create a new c1x object from this class instance.
    	 * @throws Exception on stream reading errors
    	 */
    	@Override
    	public C1XUS copy(InputStream in) throws Exception  {
    		C1XUS copy = new C1XUS(in);
    		copy.usesEncodedAdm = usesEncodedAdm;
    		copy.usesGzipResponse = usesGzipResponse;
    		return copy;
    	}
}



        
