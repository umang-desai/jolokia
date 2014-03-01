/*
 * Copyright 2009-2013 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jolokia.server.core.service.impl;

import java.util.*;

import javax.management.*;

import org.jolokia.server.core.config.ConfigKey;
import org.jolokia.server.core.config.Configuration;
import org.jolokia.server.core.detector.*;
import org.jolokia.server.core.service.request.RequestHandler;
import org.jolokia.server.core.service.request.RequestInterceptor;
import org.jolokia.server.core.util.jmx.DefaultMBeanServerAccess;
import org.jolokia.server.core.util.jmx.MBeanServerAccess;
import org.jolokia.server.core.service.*;
import org.json.simple.parser.JSONParser;

/**
 * The service manager for handling all the service organisation stuff.
 *
 * @author roland
 * @since 28.03.13
 */
public class JolokiaServiceManagerImpl implements JolokiaServiceManager {

    // Details of this agent
    private AgentDetails agentDetails;

    // Lookup for finding detectors
    private final ServerDetectorLookup detectorLookup;

    // Overall configuration
    private Configuration configuration;

    // Logger to use
    private LogHandler logHandler;

    // Restrictor to use
    private final Restrictor restrictor;

    // Whether this service manager is already initialized
    private boolean isInitialized;

    // Order in which services get initialized
    private static final Class[] SERVICE_TYPE_ORDER =
            new Class[] { ServerDetector.class, RequestHandler.class};

    // All service factories used
    private List<JolokiaServiceLookup> serviceLookups;

    // Instantiated services, categorized by type and ordered;
    private Map<Class<? extends JolokiaService>,SortedSet<? extends JolokiaService>> staticServices;

    // The lowest order service registered
    private Map<Class<? extends JolokiaService>, JolokiaService> staticLowServices;

    // Jolokia context connecting to this manager
    private JolokiaContextImpl jolokiaContext;

    // MBean registry for holding MBeans
    private MBeanRegistry mbeanRegistry;

    // Access for JMX MBeanServers
    private MBeanServerAccess mbeanServerAccess;

    /**
     * Create the implementation of a service manager
     *
     * @param pConfig configuration to use
     * @param pLogHandler the logger
     * @param pRestrictor restrictor to apply
     * @param pDetectorLookup additional lookup server detectors when the services start
     *                        (in addition to the classpath based lookup) These detectors while have a higher
     *                        precedence than the classpath based lookup. Might be null.
     */
    public JolokiaServiceManagerImpl(Configuration pConfig,
                                     LogHandler pLogHandler,
                                     Restrictor pRestrictor,
                                     ServerDetectorLookup pDetectorLookup) {
        configuration = pConfig;
        logHandler = pLogHandler;
        restrictor = pRestrictor;
        isInitialized = false;
        serviceLookups = new ArrayList<JolokiaServiceLookup>();
        staticServices = new HashMap<Class<? extends JolokiaService>, SortedSet <? extends JolokiaService>>();
        staticLowServices = new HashMap<Class<? extends JolokiaService>, JolokiaService>();
        detectorLookup = new ClasspathServerDetectorLookup(pDetectorLookup);
        // The version request handler must be always present and always be first
        addService(new VersionRequestHandler());
    }


    /**
     * Get the overall configuration
     *
     * @return configuration
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Get the loghandler used for logging
     *
     * @return loghandler
     */
    public LogHandler getLogHandler() {
        return logHandler;
    }

    /**
     * Get the restrictor
     *
     * @return restrictor
     */
    public Restrictor getRestrictor() {
        return restrictor;
    }

    /** {@inheritDoc} */
    public final synchronized void addService(JolokiaService pService) {
        Class<? extends JolokiaService> type = pService.getType();
        SortedSet<JolokiaService> servicesOfType = (SortedSet<JolokiaService>) staticServices.get(type);
        if (servicesOfType == null) {
            servicesOfType = new TreeSet<JolokiaService>();
            staticServices.put(type, servicesOfType);
        }
        servicesOfType.add(pService);
        JolokiaService pLowService = staticLowServices.get(type);
        if (pLowService == null || pLowService.getOrder() > pService.getOrder()) {
            staticLowServices.put(type,pService);
        }
    }

    /** {@inheritDoc} */
    public void addServiceLookup(JolokiaServiceLookup pLookup) {
        serviceLookups.add(pLookup);
    }

    /** {@inheritDoc} */
    public void addServices(JolokiaServiceCreator pServiceCreator) {
        for (JolokiaService service : pServiceCreator.getServices()) {
            addService(service);
        }
    }

    /** {@inheritDoc} */
    public synchronized JolokiaContext start() {
        if (!isInitialized) {

            List<ServerDetector> detectors = detectorLookup.lookup();
            mbeanServerAccess = createMBeanServerAccess(detectors);
            ServerHandle handle = detect(getDetectorOptions(),detectors,mbeanServerAccess);
            agentDetails = new AgentDetails(configuration,handle);

            // Create context and remember
            jolokiaContext = new JolokiaContextImpl(this);

            // Create the MBean registry
            mbeanRegistry = new MBeanRegistry();

            // Initialize all services in the proper order
            List<Class<? extends JolokiaService>> serviceTypes = getServiceTypes();
            for (Class<? extends JolokiaService> serviceType : serviceTypes) {
                // Initialize services
                Set<? extends JolokiaService> services = staticServices.get(serviceType);
                if (services != null) {
                    for (JolokiaService service : services) {
                        service.init(jolokiaContext);
                    }
                }
            }

            // All dynamic service factories are initialized as well. The factory itself is responsible
            // for initializing any new services coming in with the JolokiaContext
            for (JolokiaServiceLookup lookup : serviceLookups) {
                lookup.init(jolokiaContext);
            }

            isInitialized = true;
        }
        return jolokiaContext;
    }

    /** {@inheritDoc} */
    public synchronized void stop() {
        if (isInitialized) {
            try {
                mbeanRegistry.destroy();
            } catch (JMException e) {
                logHandler.error("Cannot unregister own MBeans: " + e, e);
            }
            for (JolokiaServiceLookup factory : serviceLookups) {
                factory.destroy();
            }
            for (Class<? extends JolokiaService> serviceType : getServiceTypes()) {
                Set<? extends JolokiaService> services = staticServices.get(serviceType);
                if (services != null) {
                    for (JolokiaService service : services) {
                        try {
                            service.destroy();
                        } catch (Exception e) {
                            logHandler.error("Error while stopping service " + service + " of type " + service.getType() + ": " + e,e);
                        }
                    }

                }
            }
            isInitialized = false;
        }
    }

    /**
     * Get all services of a certain type currently registered. Static services
     * are returned directly, for dynamic services a lookup to the service factory is
     * performed.
     *
     * @param pType service type to fetch
     * @return list of services detected or an empty list
     */
    public <T extends JolokiaService> SortedSet<T> getServices(Class<T> pType) {
        SortedSet<T> services = (SortedSet<T>) staticServices.get(pType);
        SortedSet<T> ret = services != null ? new TreeSet<T>(services) : new TreeSet<T>();
        for (JolokiaServiceLookup factory : serviceLookups) {
            ret.addAll(factory.getServices(pType));
        }
        return ret;
    }

    /**
     * Get a single service. If more than one service of the given type has been
     * registered, return the one with the highest order. If no one has been registered
     * return <code>null</code>
     *
     * @param pType requested service type
     * @return the requested service or null if none has been registered
     */
    public <T extends JolokiaService> T getService(Class<T> pType) {
        T ret = (T) staticLowServices.get(pType);
        int order = ret != null ? ret.getOrder() : Integer.MAX_VALUE;
        for (JolokiaServiceLookup factory : serviceLookups) {
            for (T service : (SortedSet<T>) factory.getServices(pType)) {
                if (service.getOrder() < order) {
                    ret = service;
                    order = ret.getOrder();
                }
            }
        }
        return ret;
    }

    // Access to merged MBean servers
    MBeanServerAccess getMBeanServerAccess() {
        return mbeanServerAccess;
    }


    // =============================================================================================================


    private ServerHandle detect(Map<String,Object> pConfig, List<ServerDetector> detectors, MBeanServerAccess pMBeanServerAccess) {
        for (ServerDetector detector : detectorLookup.lookup()) {
            try {
                detector.init((Map<String, Object>) pConfig.get(detector.getName()));
                ServerHandle info = detector.detect(pMBeanServerAccess);
                if (info != null) {
                    addInterceptor(detector,pMBeanServerAccess);
                    return info;
                }
            } catch (Exception exp) {
                // We are defensive here and wont stop the agent because
                // there is a problem with the server detection. A error will be logged
                // nevertheless, though.
                logHandler.error("Error while using detector " + detector.getClass().getSimpleName() + ": " + exp,exp);
            }
        }
        return DefaultServerHandle.NULL_SERVER_HANDLE;
    }

    // Add an interceptor if available
    private void addInterceptor(ServerDetector detector,MBeanServerAccess pServerAccess) {
        RequestInterceptor interceptor = detector.getRequestInterceptor(pServerAccess);
        if (interceptor  != null) {
            addService(interceptor);
        }
    }

    private MBeanServerAccess createMBeanServerAccess(List<ServerDetector> pDetectors) {
        Set<MBeanServerConnection> mbeanServers = new HashSet<MBeanServerConnection>();
        for (ServerDetector detector : pDetectors) {
            Set<MBeanServerConnection> found = detector.getMBeanServers();
            if (found != null) {
                mbeanServers.addAll(found);
            }
        }
        return new DefaultMBeanServerAccess(mbeanServers);
    }
    /**
     * Get the optional options used for detectors-default. This should be a JSON string specifying all options
     * for all detectors-default. Keys are the name of the detector's product, the values are JSON object containing
     * specific parameters for this agent. E.g.
     *
     * <pre>
     *    {
     *        "glassfish" : { "bootAmx": true  }
     *    }
     * </pre>
     *
     * @return the detector specific configuration
     */
    private Map<String,Object> getDetectorOptions() {
        String optionString = configuration.getConfig(ConfigKey.DETECTOR_OPTIONS);
        if (optionString != null) {
            try {
                return (Map<String, Object>) new JSONParser().parse(optionString);
            } catch (Exception e) {
                logHandler.error("Could not parse detetctor options '" + optionString + "' as JSON object: " + e, e);
            }
        } else {
            return Collections.emptyMap();
        }
        return null;
    }


    // Extract the order in which services should be initialized
    private List<Class<? extends JolokiaService>> getServiceTypes() {
        List<Class<? extends JolokiaService>> ret = new ArrayList<Class<? extends JolokiaService>>();
        for (Class type : SERVICE_TYPE_ORDER) {
            ret.add(type);
        }
        for (Class<? extends JolokiaService> staticType : staticServices.keySet()) {
            if (!ret.contains(staticType)) {
                ret.add(staticType);
            }
        }
        return ret;
    }

    /**
     * Return the details of this agent
     * @return the nifty details
     */
    public AgentDetails getAgentDetails() {
        return agentDetails;
    }

    /**
     * Register a MBean under a certain name to the platform MBeanServer.
     *
     * This method delegates to the {@link MBeanRegistry}.
     *
     * @param pMBean MBean to register
     * @param pOptionalName optional name under which the bean should be registered. If not provided,
     * it depends on whether the MBean to register implements {@link javax.management.MBeanRegistration} or
     * not.
     *
     * @return the name under which the MBean is registered.
     */
    public final ObjectName registerMBean(Object pMBean, String... pOptionalName)
            throws MalformedObjectNameException, NotCompliantMBeanException, InstanceAlreadyExistsException, MBeanRegistrationException {
        return mbeanRegistry.registerMBean(pMBean, pOptionalName);
    }


}
