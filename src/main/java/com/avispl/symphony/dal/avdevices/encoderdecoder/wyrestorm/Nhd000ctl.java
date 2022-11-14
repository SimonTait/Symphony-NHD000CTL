package com.avispl.symphony.dal.avdevices.encoderdecoder.wyrestorm;


import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.dal.communicator.SshCommunicator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Closeable;
import java.util.*;
import java.util.stream.Collectors;


import static com.avispl.symphony.dal.avdevices.encoderdecoder.wyrestorm.util.CommunicatorUtils.startSshServer;
import static java.util.Collections.singletonList;
import static org.apache.sshd.common.util.io.IoUtils.closeQuietly;

/**
 * Aggregated device Wyrestorm NHD-000-CTL which provides API access to all other NHD devices on a network 
 * @author Simon Tait <br> Created on June 2022
 */
public class Nhd000ctl extends SshCommunicator implements Aggregator, Monitorable, Controller {  //, Controller comes later...steal from ControlsCommunicator.java

	//DAL adapter developer has to pay an attention to mapping properties, control, statistics and monitoredStatistics properties as they are the 
	//         core ones that define what is monitored and how device is controlled
	
	//TODO :: Add the NHD-CTL-000 to the list of aggregatedDevices, give it ExtendedStatistics and ControllableProperties
	// List<Statistics> getMultipleStatistics() throws Exception return ExtendedStatistics :: This is for the NHD-CTL itself
	
	// implement the following
	// device.setProperties() and device.getProperties() :: Storage of IP address of individual NHD devices, ALSO what group they're in and sequence

	// retrieveSoftwareVersion(): Version
	
	// Following are the prerequisites to implement controlling capabilities for an aggregator to control devices behind it:
	//	1. DAL aggregator adapter implements an com.avispl.symphony.api.dal.control.Controller interface.
	//	2. AggregatedDevice.getControllableProperties() exposes properties that may be controlled on the device
	//	3. Implemented Controller.controlProperty() and Controller.controlProperties() accepts properties that are exposed by AggregatedDevice.getControllableProperties()

	private ObjectMapper objectMapper;
	private List<AggregatedDevice> aggregatedDevices;
	private List<Properties> deviceProperties;
	private List<String> commandSuccess = Arrays.asList("\r\n");
	//private List<String> commandSuccess = Arrays.asList("");
	
	//ssh stuff
    private static Closeable ssh;
    private int serverPort = 10022;
    private String hostIP = "192.168.11.244";
    private String login = "wyrestorm";
    private String pw = "networkhd";
    
    public Nhd000ctl() {
    	
    	objectMapper = new ObjectMapper();
    	aggregatedDevices = new ArrayList<>();
    	
    	this.setHost(hostIP);
    	this.setPort(serverPort);
        this.setLogin(login);
		this.setPassword(pw);
		this.setLoginSuccessList(singletonList(""));
        this.setCommandSuccessList(commandSuccess);
        this.setCommandErrorList(singletonList("unknown command"));
        
        System.out.println("new Nhd000ctl created...");

    }
    
    public String getAddress() {
        // IP address / hostname of the aggregator
        return hostIP;
    }
    
    public static void wait(int ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }
    
    @Override
    protected void internalInit() throws Exception {
        //create ssh service
    	ssh = startSshServer(serverPort);
        
        super.internalInit();
    }
    
    @Override
    protected void internalDestroy() {
        super.internalDestroy();
        //close ssh server on device destroy
        closeQuietly(ssh);
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
    	
    	String statusResult = send("config get device status", true);	
    	
    	// result has a preamble at the start which messes up parsing...remove it
        String preamble = statusResult.split("\\:")[0];
        String statusResultJSON = statusResult.replaceAll(preamble + ":\r\n", "");
        //System.out.println("statusResultJSON is :: " + statusResultJSON);
        
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> statistics = mapper.readValue(statusResultJSON, Map.class);
		//System.out.println(statistics);
		
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        extendedStatistics.setStatistics(statistics);
        
        return Collections.singletonList(extendedStatistics);
    }
    
public String deviceStatistics(String deviceAlias) throws Exception {
    	
    	String statusResult = send("config get device status "+ deviceAlias, true);	
    	// result has a preamble at the start which messes up parsing...remove it
        String preamble = statusResult.split("\\:")[0];
        String statusResultJSON = statusResult.replaceAll(preamble + ":\r\n", "");
        
        return statusResultJSON;
    }

    public List<Properties> getDeviceProperties() throws Exception {
    	// config get device info
    	return deviceProperties;
    	
    }
    
    
    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws Exception {
    	// Device aggregator has to return statistics for all devices it aggregates
        // see javadoc for com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice for all that needs to be returned
    	// https://marketplace.avisplsymphony.com/javadoc/dal/com/avispl/symphony/api/dal/dto/monitor/aggregator/AggregatedDevice.html
    	
    	System.out.println("Executing retrieveMultipleStatistics()...");
    	
        String commandResult = send("config get devicejsonstring", true);
        
        // commandResult has a preamble at the start which messes up parsing...remove it
        String preamble = commandResult.split("\\[")[0];
        String commandResultJSON = commandResult.replaceAll(preamble, "");

        final ArrayNode deviceArray = (ArrayNode) objectMapper.readTree(commandResultJSON);
        
        for (JsonNode device : deviceArray){
            aggregatedDevices.add(createDevice(device));
        }
        
        // Include the CTL as well!
        
        return aggregatedDevices;
    }
    
    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> deviceIds) throws Exception {
  
    	return retrieveMultipleStatistics().stream().filter(device -> deviceIds.contains(device.getDeviceId()))
				.collect(Collectors.toList());
    }
    
    private AggregatedDevice createDevice(JsonNode deviceJson){
    	AggregatedDevice device = new AggregatedDevice();
        
        device.setDeviceType(deviceJson.at("/deviceType").asText(""));
        device.setDeviceId(UUID.randomUUID().toString());
        device.setDeviceName(deviceJson.at("/aliasName").asText(""));
        device.setDeviceMake(deviceJson.at("/deviceMake").asText("Wyrestorm"));
        
        // Set serialNumber & deviceModel from "trueName" value
        String trueName = deviceJson.at("/trueName").asText("");
        String serialNumber = trueName.split("-")[3];
        String modelName = trueName.replaceAll("-" + serialNumber, "");
        device.setSerialNumber(serialNumber);        
        device.setDeviceModel(modelName);
        
        // MAC address is same as the serialNumber...
        device.setMacAddresses(Collections.singletonList(serialNumber));
        device.setDeviceOnline(deviceJson.at("/online").asBoolean(false));
        
        try {
        	String statusResultJSON = deviceStatistics(deviceJson.at("/aliasName").asText(""));
        	ObjectMapper mapper = new ObjectMapper();
            Map<String, String> statistics = mapper.readValue(statusResultJSON, Map.class);
    		System.out.println("Check out the statistics Man Oh Woah Oh :: " + statistics);
    		device.setStatistics(statistics);
        	}
        
        catch(Exception eggs){
        	System.out.println("Couldn't get/set statistics ..." + eggs.getMessage());
        }
        
                
        if (deviceJson.has("properties")) {
            final JsonNode properties = deviceJson.at("/properties");
            Map<String,String> deviceProperties = new HashMap<>();
            for (Iterator<String> it = properties.fieldNames(); it.hasNext(); ) {
                String field = it.next();
                if (field.equals("macAddress")){
                    device.setMacAddresses(Collections.singletonList(properties.at("/" + field).asText("")));
                }else {
                    deviceProperties.put(field, properties.at("/" + field).asText(""));
                }
            }
            device.setProperties(deviceProperties);
        }
        device.setTimestamp(System.currentTimeMillis());
        return device;
    }

    
    public static void main(String[] args) throws Exception {
    	
    	Nhd000ctl ctlDevice = new Nhd000ctl();
    	
    	try {
        	ctlDevice.init();
        	}
        
    	catch(Exception eggs){
        	System.out.println("Couldn't create/initialise ctlDevice..." + eggs.getMessage());
        	ctlDevice.destroy();
        	}
    	
    	List<Statistics> statZ= ctlDevice.getMultipleStatistics();
    	for (Statistics node : statZ)
    		System.out.println(statZ);
    	
    	//try {
	    //    statZ= ctlDevice.getMultipleStatistics(); 
	    //	System.out.println(statZ);
        //	}
    	
        //catch(Exception eggs){
        //	System.out.println("Couldn't getMultipleStatistics..." + eggs.getMessage());
	    //	ctlDevice.destroy();
        //}
    	// compose corresponding statistics object
        try {
	        List<AggregatedDevice> devices = ctlDevice.retrieveMultipleStatistics();
	        for (AggregatedDevice dev : devices){
	            System.out.println(dev.getDeviceName() + " : " + dev.getDeviceId());
	        	}
        	}
	    
        catch(Exception eggs){
	    	System.out.println("Couldn't retrieveMultipleStatistics..." + eggs.getMessage());
	    	ctlDevice.destroy();
	    	}
        
        
    	
        System.out.println("Destrooyyy");
        ctlDevice.destroy();
    }

	@Override
	public void controlProperties(List<ControllableProperty> arg0) throws Exception {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void controlProperty(ControllableProperty arg0) throws Exception {
		// TODO Auto-generated method stub
		
	}
}
