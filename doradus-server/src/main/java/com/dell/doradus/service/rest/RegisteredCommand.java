/*
 * Copyright (C) 2014 Dell, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dell.doradus.service.rest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dell.doradus.common.HttpMethod;
import com.dell.doradus.common.Utils;
import com.dell.doradus.common.rest.RESTCommand;
import com.dell.doradus.common.rest.RESTParameter;
import com.dell.doradus.service.rest.annotation.Description;
import com.dell.doradus.service.rest.annotation.ParamDescription;

/**
 * Holds information about a registered REST command. Provides methods that determine if
 * a REST request matches this command and, if it does, extracts variables from the
 * request's URL according to the command's URI template. The URI is a pattern that
 * defines fixed and variable parts. For example, consider the following command:
 * <pre>
 *      GET /{application}/{table}/_query?{query}
 * </pre>
 * This command matches HTTP GET requests that have 3 nodes in the URI path and optionally
 * a query (?) parameter. For example, suppose the following REST request is received:
 * <pre>
 *      GET /Magellan/documents/_query?q=foo+f=body
 * </pre>
 * {@link #matches(List, String, Map)} will match this request, and it will extract the
 * following variables:
 * <pre>      
 *      application = "Magellan"
 *      table = "documents"
 *      query = "q=foo+f=body"
 * </pre>
 * The path nodes and the query component, if any, are not decoded in case they contain
 * any escaped characters.
 * <p>
 * The following REST request will also match, causing the {query} variable to be empty:
 * <pre>
 *      GET /Magellan/documents/_query
 * </pre>
 * This class implements the {@link Comparable} interface, using a {@link #compareTo()}
 * method that sorts commands in the proper evaluation sequence.
 */
public class RegisteredCommand extends RESTCommand implements Comparable<RegisteredCommand> {
    private final static Logger LOGGER = LoggerFactory.getLogger(RegisteredCommand.class.getSimpleName());

    private final Class<? extends RESTCallback> m_commandClass;
    private final List<String>                  m_pathNodes = new ArrayList<>();
    private String                              m_query;

    /**
     * Create a CommandModel object that describes the REST command represented by the
     * given class.
     *  
     * @param commandClass  Subclass of {@link RESTCallback} that describes a REST
     *                      command. The class must use the {@link Description} annotation
     *                      to describe its method(s), URI, and other components.
     */
    public RegisteredCommand(Class<? extends RESTCallback> commandClass) {
        Description descAnnotation = commandClass.getAnnotation(Description.class);
        if (descAnnotation == null) {
            throw new RuntimeException("REST command class '" + commandClass.getName() +
                                       "' is missing annotation: " + Description.class.getName());
        }
        m_commandClass = commandClass;
        createCommandDescription(descAnnotation);
        buildCommandMetadata();
    }
    
    /**
     * Attempt to match the given REST request to this command and, if it does, extract
     * the requests variables. See the class description for an example. Note that this
     * method assumes the {@link HttpMethod} used by the command has already been checked.
     *  
     * @param pathNodeList  In-order URI path elements as a list. If any path nodes are
     *                      URI-encoded, they should remain that way.
     * @param query         The query expression (after the '?' sign), if any. May be null
     *                      or empty. Should remain URI-encoded.
     * @param variableMap   Variables extracted from the URI template if the request
     *                      matches. For example, if the URI template is:
     * <pre>
     *                          /{application}/_query?q={params}
     * </pre>
     *                      And the actual request passed is:
     * <pre>
     *                          /Books/_query?q=Author:smith
     * </pre>
     *                      Two elements are added to the map: {application=Books,
     *                      params=Author:smith}. URI encoding is *not* removed.
     * @return              True if the given request matches this command and variables
     *                      have been added. If the result is false, the variableMap will
     *                      not be touched.
     */
    public boolean matches(List<String> pathNodeList, String query, Map<String, String> variableMap) {
        if (pathNodeList.size() != m_pathNodes.size()) {
            return false;
        }
        Map<String, String> matchedVarMap = new HashMap<>();
        for (int index = 0; index < pathNodeList.size(); index++) {
            if (!matches(pathNodeList.get(index), m_pathNodes.get(index), matchedVarMap)) {
                return false;
            }
        }
        if (matches(query, m_query, matchedVarMap)) {
            variableMap.putAll(matchedVarMap);
            return true;
        }
        return false;
    }

    //----- Getters
    
    /**
     * Create a new REST command instance, which can be invoked to process a single REST
     * request.
     * 
     * @return  A new object that can be invoked to handle a single REST request. The
     *          object will be a concrete subclass of {@link RESTCallback}. 
     */
    public RESTCallback getNewCallback() {
        try {
            return m_commandClass.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Unable to invoke callback", e);
        }
    }

    //----- Methods for comparability
    
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RegisteredCommand)) {
            return false;
        }

        // As we compare, a variable {foo} is considered identical to a variable {bar}
        // if they occur in the same spot.
        RegisteredCommand otherCmd = (RegisteredCommand)other;
        if (!getMethodList().equals(otherCmd.getMethodList())) {
            return false;   // Different method
        }
        if (m_pathNodes.size() != otherCmd.m_pathNodes.size()) {
            return false;   // Different # of nodes
        }
        for (int index = 0; index < m_pathNodes.size(); index++) {
            if (!samePattern(m_pathNodes.get(index), otherCmd.m_pathNodes.get(index))) {
                return false;   // This node is different
            }
        }
        return samePattern(m_query, otherCmd.m_query);
    }   // equals
    
    // When comparing path nodes or query parts, two values are considered equal if either
    // (1) they are both empty, (2) they both start with '{' or, (3) neither start with
    // '{' and they have the same value (case-sensitive).
    private static boolean samePattern(String value1, String value2) {
        if (value1.length() == 0) {
            return value2.length() == 0;
        }
        if (value1.charAt(0) == '{') {
            return value2.length() > 0 && value2.charAt(0) == '{';
        }
        return value1.equals(value2);
    }   // samePattern

    /**
     * Returns a string such as "[GET,PUT] /{application}/{table}/_query -> com.foo.bar.QueryCmd". 
     */
    @Override
    public String toString() {
        return "[" + getMethodList() + "] " + getURI() + " -> " + m_commandClass.getName();
    }
    
    @Override
    public int compareTo(RegisteredCommand other) {
        // Compare the node list for each object.
        for (int index = 0; index < Math.min(m_pathNodes.size(), other.m_pathNodes.size()); index++) {
            String node1 = m_pathNodes.get(index);
            String node2 = other.m_pathNodes.get(index);
            int diff = compareNodes(node1, node2);
            if (diff != 0) {
                return diff;    // this node decides it
            }
        }
        
        // Here, all common nodes are identical.
        if (m_pathNodes.size() < other.m_pathNodes.size()) {
            return 1;   // r2 has more nodes, so sort before r1
        }
        if (m_pathNodes.size() > other.m_pathNodes.size()) {
            return -1;  // r1 has more nodes, so sort before r2
        }
        
        // Path nodes are identical. It depends on the query parameter.
        int diff = compareNodes(this.m_query, other.m_query);
        return diff;
    }
    
    // Compare the given nodes and return -1 if the first node should appear first, 1 if
    // the second should appear first, and 0 if they are identical. The nodes can be
    // path nodes are query parameters. Either node can be empty, but not null.
    private static int compareNodes(String node1, String node2) {
        assert node1 != null;
        assert node2 != null;
        
        if (node1.equals(node2)) {
            return 0;
        }
        if (node1.length() > 0 && node1.charAt(0) == '{') {
            if (node2.length() > 0 && node2.charAt(0) == '{') {
                return 0;  // Both nodes are parameters; names are irrelevant
            }
            return 1;  // r1 is a parameter but r2 is not, so r2 should come first
        }
        if (node2.length() > 0 && node2.charAt(0) == '{') {
            return -1;   // r2 is a parameter but r1 is not, so r1 should come first
        }
        return node1.compareTo(node2);  // neither node is a parameter
    }   // compareNodes

    // Similar to samePattern(), except that we are matching a candidate URI component
    // value to a component. If a match is made and the component is a variable, the
    // variable value is extracted and added to the given map.
    private static boolean matches(String value, String component, Map<String, String> variableMap) {
        if (Utils.isEmpty(component)) {
            return Utils.isEmpty(value);
        }
        if (component.charAt(0) == '{') {
            // The component is a variable, so it always matches.
            if (!Utils.isEmpty(value)) {
                String varName = getVariableName(component);
                variableMap.put(varName, value);
            }
            return true;
        }
        return component.equals(value);
    }   // matches

    // Extract the variable name from the given URI component value. For example, if the
    // value is {application}, the variable name "application" is returned. An error is
    // thrown if the trailing '}' is missing.
    private static String getVariableName(String value) {
        assert value.charAt(0) == '{';
        assert value.charAt(value.length() - 1) == '}';
        return value.substring(1, value.length() - 1);
    }   // getVariableName
    
    // Extract and save command description and parameters from the command class.
    private void buildCommandMetadata() {
        findParamDescMethods();
        parseURIIntoComponents();
    }
    
    // Look for ParamDescription annotations and attempt to call each annotated method.
    private void findParamDescMethods() {
        for (Method method : m_commandClass.getMethods()) {
            if (method.isAnnotationPresent(ParamDescription.class)) {
                try {
                    RESTParameter cmdParam = (RESTParameter) method.invoke(null, (Object[])null);
                    addParameter(cmdParam);
                } catch (Exception e) {
                    LOGGER.warn("Method '{}' for class '{}' could not be invoked: {}",
                                new Object[]{method.getName(), m_commandClass.getName(), e.toString()});
                }
            }
        }
    }
    
    // Parse the REST command URI into components for searching/comparing.
    private void parseURIIntoComponents() {
        List<String> pathNodeList = new ArrayList<String>();
        StringBuilder query = new StringBuilder();
        StringBuilder fragment = new StringBuilder();
        Utils.parseURI(getURI(), pathNodeList, query, fragment);
        for (String encodedNode : pathNodeList) {
            m_pathNodes.add(Utils.urlDecode(encodedNode));
        }
        m_query = Utils.urlDecode(query.toString());
    }
    
    // Create a CommandDescription object for the given Description annotation.
    private void createCommandDescription(Description descAnnotation) {
        setName(descAnnotation.name());
        setSummary(descAnnotation.summary());
        setMethods(descAnnotation.methods());
        setURI(descAnnotation.uri());
        setInputEntity(descAnnotation.inputEntity());
        setOutputEntity(descAnnotation.outputEntity());
        setPrivileged(descAnnotation.privileged());
        setVisibility(descAnnotation.visible());
    }
    
}   // class Command
