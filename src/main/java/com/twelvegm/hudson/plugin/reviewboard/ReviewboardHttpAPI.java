/*
 * 
 * Copyright (c) 2010, Ryan Shelley
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), the rights
 * to use, copy, modify, merge, publish, distribute, and to permit persons to 
 * whom the Software is furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.twelvegm.hudson.plugin.reviewboard;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.httpclient.params.HttpMethodParams;

/**
 * Creates an instance of the Reviewboard API.  Calls are currently executed against
 * the RESTful HTTP endpoints exposed by Reviewboard.  Authentication is done by
 * Basic Authentication with supplied username and password during instantiation.
 * 
 * Assumptions are that you have a configured version of Reviewboard associated with
 * a SCM.  Currently, this has only been tested with Perforce.
 * 
 * Currently supports a limited selection of HTTP endpoints pre-1.5 beta 2.
 * 
 * TODO: Add better/more error handling and logging
 * 
 * What this DOES currently do:
 *  1) Update an existing review with changes uploaded to the SCM (post-commit).
 *  2) Add a change description to a new diff (see #1).
 *  3) Set default reviewers on a review request.
 *  4) Set bugs on a review request.
 *  5) Set default review groups on a review request.
 *  6) Publish a review that is in draft.
 *  7) Get reviewboard users matching a query.
 *  8) Get reviewboard groups matching a query.
 *  9) Supports Perforce SCM
 *  
 * What this DOESN'T currently do:
 *  1) Create a new review request. This API currently assumes a review request already exists,
 *     having been created through the web interface or post-review (for pre/post-commits).
 *  2) Set the branch field of a review request (haven't had a need for it yet).
 *  3) Get a complete review request.
 *  4) Password encryption/decryption.  It's currently clear-text.
 *  5) Add a review to a review request.
 *  6) Add a test description to a review request.
 *  7) Add a screenshot to a review request.
 *  8) Star a review request.
 *  9) Close a review request.
 * 10) Become sentient and destroy all humans.
 * 11) Support CVS, SVN, Git, or any SCMs other than Perforce
 * 
 * Known issues / To Dos:
 *  1) The API URLs are known to be changing in the release of 1.5, so these will
 *     need to be updated accordingly.  For backward compatibility, the constants
 *     for these APIs should probably have versions included in their name so
 *     the 1.5 version of the URLs don't conflict when added.
 *  2) Some strings need to be made into constants, such as the JSON keys for
 *     retrieval of groups and users.  String literals embedded in source code
 *     are bad, mmmm-kay?
 *     
 * @author Ryan Shelley
 * @version 1.0-beta
 */
public class ReviewboardHttpAPI {

	// Base Reviewboard URL
	private final String baseUrl;
	private final URI baseUri;
	
	// Reviewboard authentication params
	private final String username;
	private final String password;
	private static final String RB_AUTH_REALM = "Web API";

	// API URL to publish an existing review request. Appended to base URL.
	private static final String RB_PUBLISH_REST_PATH = "/api/json/reviewrequests/%REVIEW_ID%/publish/";

	// API URL for setting review request reviewers. Appended to base URL.
	// Param names used for setting reviewers.
	private static final String RB_SET_REVIEWERS_PATH = "/api/json/reviewrequests/%REVIEW_ID%/draft/set/target_people/";
	private static final String RB_SET_REVIEWERS_FORM_PARAM = "value";
	
	// API URL for setting related bugs on a review request. Appended to base URL.
	// Param names used for setting bugs.
	private static final String RB_SET_BUGS_PATH = "/api/json/reviewrequests/%REVIEW_ID%/draft/set/bugs_closed/";
	private static final String RB_SET_BUGS_FORM_PARAM = "value";
	
	// API URL for setting review request groups. Appended to base URL.
	// Param names used for setting review request groups
	private static final String RB_SET_GROUPS_PATH = "/api/json/reviewrequests/%REVIEW_ID%/draft/set/target_groups/";
	private static final String RB_SET_GROUP_FORM_PARAM = "value";

	// API URL for getting groups. Appended to base URL.
	// Param names used for getting groups.
	private static final String RB_GET_GROUP_PATH = "/api/json/groups/";
	private static final String RB_GET_GROUP_QUERY_PARAM = "q";
	private static final String RB_GET_GROUP_LIMIT_PARAM = "limit";
	private static final String RB_GET_GROUP_TIMESTAMP_PARAM = "timestamp";
	private static final String RB_GET_GROUP_DISPLAYNAME_PARAM = "displayname";
	
	// API URL for setting the change description of a pending unpublished review request. Appended to base URL.
	// Param names used for setting the change description.
	private static final String RB_SET_CHANGE_DESCR_PATH = "/api/json/reviewrequests/%REVIEW_ID%/draft/set/changedescription/";
	private static final String RB_SET_CHANGE_DESCR_FORM_PARAM = "value";
	
	// API URL for getting reviewboard users to set change request reviewers. Append to base URL.
	// Param names used for getting reviewboard users.
	private static final String RB_GET_REVIEWERS_PATH = "/api/json/users/";
	private static final String RB_GET_REVIEWERS_QUERY_PARAM = "q";
	private static final String RB_GET_REVIEWERS_LIMIT_PARAM = "limit";
	private static final String RB_GET_REVIEWERS_TIMESTAMP_PARAM = "timestamp";
	private static final String RB_GET_REVIEWERS_FULLNAME_PARAM = "fullname";
	
	// String that maps to the key in a Reviewboard JSON response to obtain the status of the request.
	private static final String RB_JSON_STATUS_KEY = "stat";
	
	// Client used to execute HTTP methods against. This is shared by all API calls for this instance of 
	// the API.  Another instance of the API may have different authentication credentials, so it can't
	// be static.
	private final HttpClient client;

	// Status codes returned from Reviewboard in the JSON response body in the "stat" field.
	private static enum ReviewboardStatusCode{
		OK
		// TODO: Add the rest of the error codes.
	};
	
	/**
	 * Creates a new ReviewboardHttpAPI object used to connect to Reviewboard and 
	 * execute commands.  Tested with v1.5 beta 2.  All parameters are required.
	 * 
	 * User must have the following permissions in Reviewboard:
	 * Can add default reviewer
	 * Can change status
	 * Can edit review request
	 * Can submit as another user
	 * Can change review request
	 * Can change review request draft
	 * 
	 * @param username Username of account that has access rights to Reviewboard
	 * @param password Password of Username
	 * @param baseUrl Base URL at which Reviewboard is running
	 * @throws NullPointerException 
	 * @throws URIException 
	 */
	public ReviewboardHttpAPI(final String username, final String password, final String baseUrl) throws URIException, NullPointerException {
		this.username = username;
		this.password = password;
		this.baseUrl = baseUrl;
		
		this.baseUri = new URI(baseUrl, false);
		
		this.client = new HttpClient();
		
		HttpClientParams clientParams = new HttpClientParams();
		clientParams.setSoTimeout(300000); // 5 minutes
		this.client.setParams(clientParams);
		
		this.client.getState().setCredentials(
				new AuthScope(baseUri.getHost(), baseUri.getPort(), RB_AUTH_REALM), 
				new UsernamePasswordCredentials(this.username, this.password)
			);
	}
	
	/**
	 * Trims off a trailing "/" from a URL String
	 * 
	 * @param URL to trim
	 * @return trimmed URL
	 */
	private String trimUrl(String url){
		url = url.trim();
		if(url.endsWith("/"))
			url = url.substring(0, url.length()-1);
		
		return url;
	}
	
	/**
	 * Creates a new URI based upon the parameters supplied, replacing the supplied string parameter
	 * within the path with the replacement provided.  Param and replacement can be null, path is 
	 * required.  Param and replacement are often a review request ID.
	 * 
	 * @param path URL path to convert to URI. Can contain one arbitrary parameter to be replaced
	 * @param param String within the path to replace with the replacement argument
	 * @param replacement String to replace the param argument with in the path
	 * @return URI if a valid URL is generated
	 */
	private URI createUri(String path, String param, String replacement){
		URI uri = null;
		
		if(param == null)
			param = "";
		if(replacement == null)
			replacement = "";
		
		try {
			uri = new URI(trimUrl(this.baseUrl) + path.replace(param, replacement), false);
		} catch (URIException e) {
			e.printStackTrace();
		} catch (NullPointerException e) {
			e.printStackTrace();
		}
		return uri;
	}
	
	/**
	 * Executes a POST method against Reviewboard.  This method is used for anything
	 * that changes a review request in Reviewboard.
	 * 
	 * @param uri URI to execute the POST against
	 * @param params Params to pass in the POST
	 * @return true if successful, false if otherwise
	 * @throws URIException
	 */
	private boolean executePostApiCall(final URI uri, final NameValuePair[] params) throws URIException{
		
		boolean status = false;
		
		PostMethod post = new PostMethod(uri.getURI());
		post.addParameters(params);
		JSONObject response = executeApiCall(JSONObject.class, post);
		
		if(response != null){
			
			ReviewboardStatusCode code = null;
			code = resolveResponseStatus(response);
			
			status = (response != null && ReviewboardStatusCode.OK.equals(code));
		}
		
		return status;
	}
	
	/**
	 * Executes a GET method against Reviewboard.  This method is used for anything
	 * that queries information from Reviewboard.
	 * 
	 * @param uri URI to execute the GET against
	 * @param params Params to pass in the querystring
	 * @return JSONObject containing the parsed response body of a valid response, null otherwise.
	 * @throws URIException
	 */
	private JSONObject executeGetApiCall(final URI uri, final NameValuePair[] params) throws URIException{
		
		JSONObject response = null;
		GetMethod get = new GetMethod(uri.getURI());

		HttpMethodParams methodParams = new HttpMethodParams();
		get.setQueryString(params);
		get.setParams(methodParams);
		
		response = executeApiCall(JSONObject.class, get);
		if(response != null){
			
			ReviewboardStatusCode status = null;
			status = resolveResponseStatus(response);
			
			if(response == null || !ReviewboardStatusCode.OK.equals(status)){
				response = null;
			}
		}
		
		return response;
	}	
	
	/**
	 * Executes an arbitrary HTTP method.  HTTP Method should be pre-configured with
	 * the URI and parameters.
	 * 
	 * @param <T> Type of return value.
	 * @param returnType Type of return value. Currently only Boolean and String are supported.
	 * @param method HTTP method to execute.
	 * @return if returnType is Boolean and status code is 2XX/3XX, returns true otherwise false; if returnType is String and status code is 2XX, returns response body otherwise null
	 */
	@SuppressWarnings("unchecked")
	private <T> T executeApiCall(final Class<T> returnType, final HttpMethod method){

		if( !(returnType == Boolean.class) && !(returnType == String.class) && !(returnType == JSONObject.class) )
			throw new UnsupportedOperationException("This method only supports return types of Boolean, String or JSONObject, currently.");

		T returnValue = null;
		
		try { 

			method.setDoAuthentication( true );

			int statusCode = client.executeMethod(method);

			if(statusCode >= 200 && statusCode < 400){
				JSONObject jsonResponse = parseStringToJSONObject(method.getResponseBodyAsString());
				
				ReviewboardStatusCode status = null;
				if(jsonResponse != null)
					status = resolveResponseStatus(jsonResponse);
				
				if(jsonResponse != null && ReviewboardStatusCode.OK.equals(status)){
					if(returnType == String.class)
						returnValue = (T)method.getResponseBodyAsString();
					else if(returnType == Boolean.class)
						returnValue = (T)Boolean.TRUE;
					else if(returnType == JSONObject.class)
						returnValue = (T)jsonResponse;
				}
			}
		} catch (Exception e) { 
			e.printStackTrace();
		} finally {
			method.releaseConnection();
		}

		return returnValue;
	}
	
	/**
	 * Converts a JSON string into a JSONObject.
	 * 
	 * @param str String to convert to JSONObject
	 * @return JSONObject if string was converted properly, null otherwise
	 */
	private static JSONObject parseStringToJSONObject(final String str){
		
		JSONObject o = null;
		if(str != null && !str.trim().isEmpty()){
			try{
			o = JSONObject.fromObject(str);
			}catch(JSONException e){
				e.printStackTrace();
			}
		}
		return o;
	}
	
	/**
	 * Resolves the status of a Reviewboard response from a JSONObject into it's
	 * associated Enumeration.
	 * 
	 * @param response JSON response from Reviewboard, parsed into a JSONObject
	 * @return ReviewboardStatusCode matching the status in the "stat" param.
	 * @see #parseStringToJSONObject(String)
	 */
	private static ReviewboardStatusCode resolveResponseStatus(final JSONObject response){
	
		if(response == null)
			throw new IllegalArgumentException("JSON Response argument cannot be null.");
		
		ReviewboardStatusCode code = null;
		try{
			String status = response.getString(RB_JSON_STATUS_KEY);
			if(status != null && !status.isEmpty())
				code = ReviewboardStatusCode.valueOf(status.trim().toUpperCase());
		}catch(IllegalArgumentException e){
			e.printStackTrace();
		}catch(JSONException e){
			e.printStackTrace();
		}
		
		return code;
	}
	
	/**
	 * Retrieves a list of Reviewboard Groups that match the query argument. Group names are case-sensitive.
	 * 
	 * @param query part of string to search Reviewboard to match group names against.
	 * @return Set of matching groups, or empty list if none were found. Group names are case-sensitive.
	 */
	public Set<String> getGroups(final String query){
		
		Set<String> groups = new HashSet<String>();
		
		URI uri = this.createUri(RB_GET_GROUP_PATH, null, null);
		
		NameValuePair[] params = new NameValuePair[]{
				new NameValuePair(RB_GET_GROUP_QUERY_PARAM, query),
				new NameValuePair(RB_GET_GROUP_LIMIT_PARAM, "150"),
				new NameValuePair(RB_GET_GROUP_TIMESTAMP_PARAM, String.valueOf((new Date()).getTime())),
				new NameValuePair(RB_GET_GROUP_DISPLAYNAME_PARAM, "0")
		};
		
		try {
			JSONObject response = this.executeGetApiCall(uri, params);
			if(response != null){
				List<Object> jsonGrps = response.getJSONArray("groups");
				for(Object jsonGrp : jsonGrps){
					if(jsonGrp instanceof JSONObject)
						groups.add( ((JSONObject)jsonGrp).getString("name") );
				}
			}else{
				System.out.println("Error response from Reviewboard Group query: " + response);
			}
		} catch (URIException e) {
			e.printStackTrace();
		}
		
		return groups;
	}
	
	/**
	 * Retrieves a list of Reviewboard users who match the query argument. Usernames are case-sensitive.
	 * 
	 * @param query part of usernames to match against Reviewboard users.
	 * @return Set of matching usernames or empty list if none were found. Usernames are case-sensitive.
	 */
	public Set<String> getReviewers(final String query){
		
		Set<String> users = new HashSet<String>();
		
		URI uri = this.createUri(RB_GET_REVIEWERS_PATH, null, null);
		
		NameValuePair[] params = new NameValuePair[]{
				new NameValuePair(RB_GET_REVIEWERS_QUERY_PARAM, query),
				new NameValuePair(RB_GET_REVIEWERS_LIMIT_PARAM, "150"),
				new NameValuePair(RB_GET_REVIEWERS_TIMESTAMP_PARAM, String.valueOf((new Date()).getTime())),
				new NameValuePair(RB_GET_REVIEWERS_FULLNAME_PARAM, "0")
		};
		
		try {
			JSONObject response = this.executeGetApiCall(uri, params);
			if(response != null){
				List<Object> jsonUsers = response.getJSONArray("users");
				for(Object jsonUser : jsonUsers){
					if(jsonUser instanceof JSONObject)
						users.add( ((JSONObject)jsonUser).getString("username") );
				}
			}else{
				System.out.println("Error response from Reviewboard User query: " + response);
			}
		} catch (URIException e) {
			e.printStackTrace();
		}
		
		return users;
	}
	
	/**
	 * Sets one or more groups as default review groups on a review request.  The groups
	 * argument should be a comma-delimited string of valid Reviewboard groups.  Use
	 * {@link #getGroups(String)} to validate input before passing to this method.  The
	 * review request being modified should be in draft and not yet submit.  This method
	 * will overwrite any existing groups on the review request.
	 * 
	 * @param review Review to modify set the review groups on
	 * @param groups String of comma-separated groups to set
	 * @return true if successful, false otherwise
	 */
	public boolean setGroups(final ReviewRequest review, final String groups){
		
		boolean status = false;
		
		URI uri = this.createUri(RB_SET_GROUPS_PATH, "%REVIEW_ID%", review.getReviewBoardID().toString());
		
		NameValuePair[] params = new NameValuePair[]{
				new NameValuePair(RB_SET_GROUP_FORM_PARAM, groups)
		};
		
		try {
			status = this.executePostApiCall(uri, params);
		} catch (URIException e) {
			e.printStackTrace();
		}
		
		return status;
	}
	
	/**
	 * Sets one or more users as default reviewers on a review request.  The reviewers
	 * argument should be a comma-delimited string of valid Reviewboard users.  Use
	 * {@link #getReviewers(String)} to validate input before passing to this method.  The
	 * review request being modified should be in draft and not yet submit.  This method
	 * will overwrite any existing reviewers on the review request.
	 * 
	 * @param review Review to set the reviewers on
	 * @param reviewers String of comma-separated Reviewboard users to set
	 * @return true if successful, false otherwise
	 */
	public boolean setReviewers(final ReviewRequest review, final String reviewers){
		
		boolean status = false;
		
		URI uri = this.createUri(RB_SET_REVIEWERS_PATH, "%REVIEW_ID%", review.getReviewBoardID().toString());
		
		NameValuePair[] params = new NameValuePair[]{
				new NameValuePair(RB_SET_REVIEWERS_FORM_PARAM, reviewers)
		};
		
		try {
			status = this.executePostApiCall(uri, params);
		} catch (URIException e) {
			e.printStackTrace();
		}
		
		return status;
	}
	
	/**
	 * Sets the change description on a review that is being updated and in draft.
	 * This method is used to add a description to an updated diff of an existing
	 * review request.
	 * 
	 * @param review Review to add the description to
	 * @param description Description to add to the pending diff
	 * @return true if successful, false otherwise
	 */
	public boolean setChangeDescription(final ReviewRequest review, final String description){
		
		boolean status = false;
		
		URI uri = this.createUri(RB_SET_CHANGE_DESCR_PATH, "%REVIEW_ID%", review.getReviewBoardID().toString());
		
		NameValuePair[] params = new NameValuePair[]{
				new NameValuePair(RB_SET_CHANGE_DESCR_FORM_PARAM, description)
		};
		
		try {
			status = this.executePostApiCall(uri, params);
		} catch (URIException e) {
			e.printStackTrace();
		}
		
		return status;
	}
	
	/**
	 * Sets the related bugs of a pending review request.  This is usually the
	 * JIRA/Bugzilla/etc ID(s) associated with the review request and often the
	 * same as the External ID.
	 * 
	 * @param review Review to add the bug(s) to
	 * @param bugs String of comma-separated bug IDs
	 * @return
	 */
	public boolean setBugs(final ReviewRequest review, final String bugs){

		boolean status = false;
		
		URI uri = this.createUri(RB_SET_BUGS_PATH, "%REVIEW_ID%", review.getReviewBoardID().toString());
		
		NameValuePair[] params = new NameValuePair[]{
				new NameValuePair(RB_SET_BUGS_FORM_PARAM, bugs)
		};
		
		try {
			status = this.executePostApiCall(uri, params);
		} catch (URIException e) {
			e.printStackTrace();
		}
		
		return status;
	}
	
	/**
	 * Publishes a review request in draft.  If the review request is configured to
	 * notify reviewers and review groups, they will be sent emails.
	 * 
	 * @param review Review to publish
	 * @return true if successful, false otherwise
	 */
	public boolean publishReview(final ReviewRequest review){

		boolean status = false;
		
		URI uri = this.createUri(RB_PUBLISH_REST_PATH, "%REVIEW_ID%", review.getReviewBoardID().toString());
		
		NameValuePair[] params = new NameValuePair[]{};
		
		try {
			status = this.executePostApiCall(uri, params);
		} catch (URIException e) {
			e.printStackTrace();
		}
		
		return status;
	}	
}