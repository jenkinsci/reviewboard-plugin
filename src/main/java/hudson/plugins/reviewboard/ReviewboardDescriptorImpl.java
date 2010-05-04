package hudson.plugins.reviewboard;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.apache.commons.httpclient.URIException;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.google.common.collect.ImmutableSet;
import com.twelvegm.hudson.plugin.reviewboard.ReviewboardHttpAPI;

/**
 * Descriptor for {@link ReviewboardPublisher}. Used as a singleton.
 * The class is marked as public so that it can be accessed from views.
 *
 * @author Ryan Shelley
 * @version 1.0
 */
@Extension 
public final class ReviewboardDescriptorImpl extends BuildStepDescriptor<Publisher> {

    private String url; 
    private String username;
    private String password;
    private String cmdPath; 
    
	private transient Set<String> reviewboardUsers = null;
	private transient Set<String> reviewboardGroups = null;
	
	private transient ReviewboardHttpAPI rbApi = null;

    public ReviewboardDescriptorImpl(){
    	super(ReviewboardPublisher.class);
    	load();
    	
    	this.populateReviewboardLists();
    }

    /**
     * Loads all users and groups from Reviewboard for use with dropdown population
     * and validation checks.  Sets are immutable.
     */
    private void populateReviewboardLists(){
    
    	if(this.isPluginConfigured()){
    		
    		try {
				reviewboardGroups = Collections.unmodifiableSet(this.getReviewboardAPI().getGroups(""));
				reviewboardUsers = Collections.unmodifiableSet(this.getReviewboardAPI().getReviewers(""));
			} catch (URIException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (NullPointerException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	}
    }
    
    /**
     * Validates the regular expression supplied is a valid pattern for the external ID
     * as set on the Build's configuration page.
     * 
     * Example description:  FMYHUD-123 fixes issues with build
     * Example expression :  FMYHUD-[0-9]+
     * 
     * The result will be "FMYHUD-123" is extracted and used as an external key to map future changes
     * to existing review requests (so if someone submits several change requests with the same external
     * ID, the original review request is updated instead of new review requests being created).
     * 
     * @param keyRegEx regular expression matching the external ID to extract from the change description
     * @return FormValidation.ok if the regular expression is valid, FormValidation.error if not
     * @throws IOException
     * @throws ServletException
     */
    public FormValidation doCheckKeyRegEx(@QueryParameter String keyRegEx) throws IOException, ServletException {
    	if(keyRegEx != null && !keyRegEx.isEmpty()){
    		try{
    			Pattern.compile(keyRegEx);
    			return FormValidation.okWithMarkup("<span style=\"color:green;\">Regular Expression is valid.</span>");
    		}catch(PatternSyntaxException pse){
    			return FormValidation.error("Regular Expression is not valid.");
    		}
    	}
    	
    	return FormValidation.ok();
    }
    
    /**
     * Validates that the command supplied exists and can be executed.  Searches the 
     * path environment for the executable.  Complete path is not necessary.
     * 
     * @param cmdPath command to execute post-review, and is often just "post-review.exe"
     * @return FormValidation.ok if the command is found, FormValidation.error if not
     * @throws IOException
     * @throws ServletException
     */
    public FormValidation doCheckCmdPath(@QueryParameter String cmdPath) throws IOException, ServletException {
    	
    	if(this.url != null && !this.url.isEmpty() && cmdPath != null && !cmdPath.isEmpty()){
   			return FormValidation.validateExecutable(cmdPath);
    	}else{
    		return FormValidation.error("Path to post-review must be supplied.");
    	}
    }
    
    /**
     * Validates that the number of days before a review is considered stale is greater 
     * than or equal to -1.  -1 = never
     * 
     * @param cmdPath command to execute post-review, and is often just "post-review.exe"
     * @return FormValidation.ok if the command is found, FormValidation.error if not
     * @throws IOException
     * @throws ServletException
     */
    public FormValidation doCheckDaysBeforeStaleReview(@QueryParameter Integer daysBeforeStaleReview) throws IOException, ServletException {
    	
    	if(daysBeforeStaleReview != null && daysBeforeStaleReview >= -1){
   			return FormValidation.ok();
    	}else if (daysBeforeStaleReview == null){
    		return FormValidation.ok();
    	}else{
    		return FormValidation.error("Cannot be less than -1");
    	}
    }
    
    /**
     * Validates that the Reviewboard URL supplied is available.
     * 
     * @param url URL to reviewboard instance
     * @return FormValidation.ok if the URL was available, FormValidation.error if not
     * @throws IOException
     * @throws ServletException
     */
    public FormValidation doCheckUrl(@QueryParameter String url) throws IOException, ServletException {
    	
    	if(url != null && !url.isEmpty()){
			try {
				if(isValidURL(url))
					return FormValidation.ok();
				else
					return FormValidation.error("Connection to Reviewboard failed.");
			} catch(MalformedURLException mue){
				return FormValidation.error("Malformed URL.");
			} catch (URISyntaxException use) {
				return FormValidation.error("Invalid URL syntax.");
			} catch (UnknownHostException uhe) {
				return FormValidation.error("Unknown host.");
			} catch (Exception e){
				return FormValidation.error("Connection to Reviewboard failed. " + e.getMessage());
			}
    	}
		
		return FormValidation.ok();
	}
    
    /**
     * Validates that the reviewers entered match existing Reviewboard users.
     * 
     * @param defaultReviewers Comma-delimited list of reviewers to check
     * @return FormValidation.ok if field is empty or users all exist, otherwise FormValidation.error
     * @throws IOException
     * @throws ServletException
     */
    public FormValidation doCheckDefaultReviewers(@QueryParameter String defaultReviewers) throws IOException, ServletException {
    	
    	if(defaultReviewers != null && defaultReviewers.trim().length() > 0){
    		
    		String[] userArray = defaultReviewers.split(",");
    		for(String user: userArray){
    			user = user.trim();
        		Set<String> users = this.getReviewboardAPI().getReviewers(user);
        		if(users == null || users.size() == 0)
        			return FormValidation.error("Reviewer \"" + user + "\" was not found in Reviewboard.  Usernames are case-sensitive.");
        		else if(users.size() > 1)
        			return FormValidation.error("Reviewer \"" + user + "\" matched more than one user in Reviewboard.  Matched values: " + users.toString());
        		else if(users.contains(user))
        			continue;
        		else
        			return FormValidation.error("Reviewer \"" + user + "\" did not match any Reviewboard users.  Usernames are case-sensitive.  Did you mean " + users.toArray()[0] + "?");
        	}
    	}
    	return FormValidation.ok();  
    }
    
    /**
     * Validates that the review groups entered match existing Reviewboard groups.
     * 
     * @param defaultReviewGroups Comma-delimited list of groups to check
     * @return FormValidation.ok if field is empty or groups all exist, otherwise FormValidation.error
     * @throws IOException
     * @throws ServletException
     */
    public FormValidation doCheckDefaultReviewGroups(@QueryParameter String defaultReviewGroups) throws IOException, ServletException {
		
    	if(defaultReviewGroups != null && defaultReviewGroups.trim().length() > 0){
    		
    		String[] groupArray = defaultReviewGroups.split(",");
    		for(String group: groupArray){
    			group = group.trim();
        		Set<String> groups = this.getReviewboardAPI().getGroups(group);
        		if(groups == null || groups.size() == 0)
        			return FormValidation.error("Group \"" + group + "\" was not found in Reviewboard.");
        		else if(groups.size() > 1)
        			return FormValidation.error("Group \"" + group + "\" matched more than one group in Reviewboard.  Matched values: " + groups.toString());
        		else if(groups.contains(group))
        			continue;
        		else
        			return FormValidation.error("Group \"" + group + "\" did not match any Reviewboard groups.  Did you mean " + groups.toArray()[0] + "?");
        	}
    	}
    	return FormValidation.ok();  
    }
    
    public FormValidation doCheckForceUpdateOverride(@QueryParameter boolean forceUpdateOverride, @QueryParameter boolean skipUnflaggedChanges){
    	
    	if(forceUpdateOverride && !skipUnflaggedChanges)
    		return FormValidation.warning("This is only active when \"Skip unflagged changes\" is enabled.  This setting will be ignored.");
    	
    	return FormValidation.ok();
    }
    
    public FormValidation doCheckSkipUnflaggedChanges(@QueryParameter boolean forceUpdateOverride, @QueryParameter boolean skipUnflaggedChanges){
    	
    	if(forceUpdateOverride && !skipUnflaggedChanges)
    		return FormValidation.warning("The option \"Require RB_UPDATE\" will be ignored since it requires that this option be enabled to be active");
    	
    	return FormValidation.ok();
    }
    
    public boolean isApplicable(Class<? extends AbstractProject> aClass) {
        // indicates that this builder can be used with all kinds of project types 
        return true;
    }

    /**
     * This human readable name is used in the configuration screen.
     * 
     * @return name of plugin
     */
    public String getDisplayName() {
        return "Review Board Publisher";
    }

    /**
     * Configures the Reviewboard plugin with parameters supplied on the Global configuration page of Hudson.
     * 
     * @param req Form submission request
     * @param o JSON object containing values from global configuration
     */
    @Override
    public boolean configure(StaplerRequest req, JSONObject o) throws FormException {
        // to persist global configuration information,
        // set that to properties and call save().
        url = o.getString("url");
        username = o.getString("username");
        password = o.getString("password");
        cmdPath = o.getString("cmdPath");
        
        try {
			rbApi = new ReviewboardHttpAPI(username, password, url);
		} catch (URIException e) {
			throw new FormException(e, e.getMessage());
		} catch (NullPointerException e) {
			throw new FormException(e, e.getMessage());
		}
        
		// Save to global config file
        save();
        
        return super.configure(req,o);
    }
    
    /**
     * Returns the reviewboard URL
     * 
     * @return reviewboard URL
     */
    public String getUrl() {
    	return url;
    }
    
    /**
     * Returns the reviewboard username
     * 
     * @return reviewboard username
     */
    public String getUsername() {
    	return username;
    }
    
    /**
     * Returns the reviewboard password
     * 
     * @return reviewboard password
     */
    public String getPassword() {
    	return password;
    }
    
    /**
     * Returns the reviewboard command path
     * 
     * @return reviewboard command path
     */
    public String getCmdPath() {
    	return cmdPath;
    }
    
    public Set<String> getReviewboardGroups(){
    	return this.reviewboardGroups;
    }
    
    public Set<String> getReviewboardUsers(){
    	return this.reviewboardUsers;
    }
    
    /**
     * Returns a configured Reviewboard API
     * 
     * @return Configured and ready-to-use Reviewboard API, or null if an error occurred creating it
     * @throws URIException
     * @throws NullPointerException
     */
    protected ReviewboardHttpAPI getReviewboardAPI() throws URIException, NullPointerException{
    	if(rbApi == null)
    		rbApi = new ReviewboardHttpAPI(this.username, this.password, this.url);
    	
    	return rbApi;
    }
    
    /**
     * Validates a URL is available and responds with a 200 error code.
     * 
     * @param url to check
     * @return true if response code is >= 200 and < 300, otherwise false
     * @throws URISyntaxException
     * @throws MalformedURLException
     * @throws IOException
     */
    protected boolean isValidURL(String url) throws URISyntaxException, MalformedURLException, IOException{
		URI uri = new URI(url);
		HttpURLConnection conn = (HttpURLConnection)uri.toURL().openConnection();
		
		try{
			return (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300);
		}finally{
			if(conn != null)
				conn.disconnect();
		}
		
    }
    
    /**
     * Validates that the saved URL is available.  This is executed before the build tries to
     * create a reviewboard request to ensure reviewboard is available.
     * 
     * @return true if the URL is available, false otherwise
     */
    protected boolean isSavedURLValid(){
		try {
			return this.isValidURL(this.url);
		} catch (URISyntaxException e) {
			return false;
		} catch (IOException e) {
			return false;
		}
    }
    
    /**
     * Validates that the command path to post-review is available and executable.
     * 
     * @return true if available, false otherwise
     */
    protected boolean isSavedCommandPathValid(){
    	return FormValidation.validateExecutable(cmdPath).kind.compareTo(FormValidation.Kind.OK) == 0;
    }
    
    /**
     * Validates that the plugin is properly configured and reviewboard is available.
     * This is called before the plugin is executed after a build to ensure that
     * it can be executed properly.
     * 
     * @return true if the plugin is properly configured and reviewboard is available, false otherwise
     */
    protected boolean isPluginConfigured(){
    	return (isSavedURLValid() && isSavedCommandPathValid());
    }
}