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

package hudson.plugins.reviewboard;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.plugins.perforce.HudsonPipedOutputStream;
import hudson.plugins.perforce.PerforceChangeLogEntry;
import hudson.plugins.perforce.PerforceSCM;
import hudson.plugins.perforce.PerforceSCM.PerforceSCMDescriptor;
import hudson.scm.AbstractScmTagAction;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.util.ArgumentListBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Creates a Publisher that will notify inspect the build for change sets, and if included,
 * notifies a Reviewboard (http://www.reviewboard.org) installation to either creates a new
 * review request or update an existing review request.
 * 
 * Currently, this plugin only supports Perforce changelists.
 *
 * TODO: Add better/more error handling and logging
 * 
 * What this DOESN'T do:
 * 1) Your dishes, dirty laundry or homework
 * 
 * @author Ryan Shelley
 * @version 1.0
 */
public class ReviewboardPublisher extends Notifier {

	// Pattern of response from reviewboard's post-review command used to parse the review ID.
	// Example response pattern must match:  Review request #36 posted.
	private static String reviewBoardIDRegEx = "[\\w\\s\\.]+#([0-9]+)[\\w\\s\\.]+";
	private static Pattern reviewBoardIDRegExPattern = Pattern.compile(reviewBoardIDRegEx);
	
	// Pattern of error response from failed attempt to submit a new, or update an existing, review request
	private static String reviewBoardHTTPErrorRegEx = "[a-zA-Z\\s]+([\\d]+):[a-zA-Z\\s]+([\\d]+)";
	private static Pattern reviewBoardHTTPErrorPattern = Pattern.compile(reviewBoardHTTPErrorRegEx);
	
	// Pattern of a key in the change description that maps to an External ID
	private String keyRegEx = "";
	private Pattern keyRegExPattern = null;
	
	// Default params used when creating or publishing reviews
	private String defaultReviewGroups = "";
	private String defaultReviewers = "";
	private boolean authorAsReviewer = true;
	private boolean publishReviews = true;
	
    // Commented out debugPostReview param currently because debug mode hangs Hudson due to leaking file handles
	//private boolean debugPostReview = false;
	
	// Skip creating new review requests if the change description doesn't have and explicit RB_NEW flag.
	private boolean skipUnflaggedChanges = false;
	
	// If defaultActionOverrideSkip is true, this flag will determine if change descriptions must include
	// a RB_UPDATE override flag to update a Review Request created with RB_NEW, or if we automatically
	// update a review once it was created with RB_NEW even without RB_UPDATE.
	private boolean forceUpdateOverride = false;
	
	// Defines the number of days since last review update (through Hudson) before the review
	// is considered stale and a new review request is created with the same external ID.
	// -1 = no expiration until build containing mapping is removed.
	private Integer daysBeforeStaleReview = -1;
	
	// If creating or updating review requests in Reviewboard fail, should we also fail the build?
	private boolean failBuildOnReviewboardError = false;
	
	/**
	 * Defines override actions that the plugin can inspect the change description for to
	 * allow the author of the change to override the default behavior of {@link #defaultActionOverrideSkip}.
	 * 
	 * If defaultActionOverrideSkip is false, the default action is RB_NONE.  This means
	 * that changes will automatically create or update reviews for every unflagged change.
	 * 
	 * If defaultActionOverrideSkip is true, the default action is RB_SKIP.  This means
	 * that changes that are unflagged are automatically skipped and no review request
	 * will be created or updated.
	 * 
	 * In either case, if a change description contains an override flag, it will override
	 * the default setting of defaultActionOverrideSkip and perform the action requested
	 * in the change description.
	 * 
	 * Ex: Change description "FMYHUD-100 RB_NEW Made sweeping changes to architecture"
	 * would cause the plugin to force the creation of a new review request even if a
	 * review request already exists mapped to FMYHUD-100. 
	 * 
	 * @author rshelley
	 */
	private static enum ActionOverrideFlag{
		// Skip creating/updating any Reviewboard Review Requests even if one exists for External ID
		RB_SKIP,
		
		// Force the creation of a new Reviewboard Review Request based upon External ID
		RB_NEW,
		
		// Force the update of an existing Reviewboard Review Request, or create new if none exists matching External ID
		// Currently, RB_UPDATE == RB_NONE (in functionality) if defaultActionOverrideSkip == false
		// Otherwise, RB_UPDATE is required in change descriptions to update existing Review Requests
		RB_UPDATE,
		
		// No special handling. Actions are dependent upon defaultActionOverrideSkip.
		RB_NONE;
	}
	
	/**
	 * Builds the Publisher with current build configuration options.
	 * 
	 * @param keyRegEx Regular expression to match against changelist description looking to extract an external ID (such as a JIRA ID)
	 */
    @DataBoundConstructor
    // Commented out debugPostReview param currently because debug mode hangs Hudson due to leaking file handles
    //public ReviewboardPublisher(String keyRegEx, Integer daysBeforeStaleReview, String defaultReviewGroups, String defaultReviewers, boolean authorAsReviewer, boolean publishReviews, boolean skipUnflaggedChanges, boolean forceUpdateOverride, boolean failBuildOnReviewboardError, boolean debugPostReview) {
    public ReviewboardPublisher(String keyRegEx, Integer daysBeforeStaleReview, String defaultReviewGroups, String defaultReviewers, boolean authorAsReviewer, boolean publishReviews, boolean skipUnflaggedChanges, boolean forceUpdateOverride, boolean failBuildOnReviewboardError) {
    	
    	this.defaultReviewGroups = defaultReviewGroups;
    	this.daysBeforeStaleReview = daysBeforeStaleReview;
    	this.authorAsReviewer = authorAsReviewer;
    	this.defaultReviewers = defaultReviewers;
    	this.publishReviews = publishReviews;
    	this.skipUnflaggedChanges = skipUnflaggedChanges;
    	this.forceUpdateOverride = forceUpdateOverride;
    	this.failBuildOnReviewboardError = failBuildOnReviewboardError;
    	//this.debugPostReview = debugPostReview;
    	
    	if(daysBeforeStaleReview == null)
    		this.daysBeforeStaleReview = -1;
    	else
    		this.daysBeforeStaleReview = daysBeforeStaleReview;
    	
    	if(keyRegEx != null && !keyRegEx.isEmpty()){
    		try{
    			keyRegExPattern = Pattern.compile("(" + keyRegEx + ")");
    	    	this.keyRegEx = keyRegEx;
    		}catch(PatternSyntaxException pse){
    			pse.printStackTrace();
    		}
    	}
    }

    public String getKeyRegEx() {
		return keyRegEx;
	}
    
    public Integer getDaysBeforeStaleReview() {
		return daysBeforeStaleReview;
	}
    
    public String getDefaultReviewGroups() {
		return defaultReviewGroups;
	}
    
    public String getDefaultReviewers() {
		return defaultReviewers;
	}
    
    public boolean getAuthorAsReviewer() {
		return authorAsReviewer;
	}
    
    public boolean getPublishReviews(){
    	return publishReviews;
    }
    
	public boolean getForceUpdateOverride(){
		return forceUpdateOverride;
	}
	
	public boolean getSkipUnflaggedChanges(){
		return skipUnflaggedChanges;
	}
    
    public Set<String> getReviewboardUsers(){
    	return this.getDescriptor().getReviewboardUsers();
    }
    
    public Set<String> getReviewboardGroups(){
    	return this.getDescriptor().getReviewboardGroups();
    }
    
    public boolean getFailBuildOnReviewboardError() {
		return failBuildOnReviewboardError;
	}
    
    // Commented out debugPostReview param currently because debug mode hangs Hudson due to leaking file handles
    /*
    public boolean getDebugPostReview() {
    	return debugPostReview;
    }
    */
    
    @Override
    public ReviewboardDescriptorImpl getDescriptor() {
        return (ReviewboardDescriptorImpl)super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
    	return BuildStepMonitor.BUILD;
    }
    
    /**
     * Executes after the build is complete to send changelist changes to Reviewboard.
     * 
     * @param build Recently executed build
     * @param launcher Launcher used to execute command shell processes (like post-review.exe)
     * @param listener Listener to notify build process of events
     * @return true if successful (or action skipped by override flag), or false if failed
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

    	listener.getLogger().println("---- Beginning Reviewboard Plugin ----");

    	boolean status = false;
    	
    	try{
    	// If the plugin has been properly configured, we can run the task...
    	if(!this.getDescriptor().isPluginConfigured())
    		listener.getLogger().println("Plugin is not configured properly, skipping action");
    	else
    		status = processChangeset(build, launcher, listener);
    	} catch (Exception e){
    		// If an exception occurred and wasn't caught lower, this will catch it, show the error, and leave the state as "false"
    		e.printStackTrace(listener.getLogger());
    	}
    	
    	listener.getLogger().println("---- Ending Reviewboard Plugin: " + ((status)? "SUCCESSFUL" : "FAILURE") + " ----");

    	// Override the status on the build to "true" if creating/updating a review request failed and 
    	// we're configured to not fail the build on failed reviewboard requests.
    	status = (!status && !this.failBuildOnReviewboardError) ? true : status;
    	
    	return status;
    }
    
    public boolean processChangeset(AbstractBuild build, Launcher launcher, BuildListener listener) {    	

		String externalID = "";
		Long changeListID = 0L;
		Long existingReviewBoardID = null;
		String author = "";
		String changeDescr = "";
		Collection<String> files = null;
		
		// Obtain the list of changes associated with the current build
		ChangeLogSet<? extends Entry> changeSet = (ChangeLogSet<? extends Entry>)build.getChangeSet();
		
		// No changes to send to Reviewboard
		if(changeSet == null)
			return true;
		
		// Iterate through the list of changes and send each to Reviewboard.  In the case of Perforce,
		// each changelist is a separate entry in the change set.
		Iterator<? extends Entry> iEntries = changeSet.iterator();
		while(iEntries.hasNext()){
    		Entry entry = iEntries.next();
	        
    		// Search the change message for an external ID
			externalID = this.getExternalKeyFromChangeDescr(entry.getMsg(), listener.getLogger());
			if(externalID == null)
				continue; // Changelist doesn't match the pattern configured, so there's nothing to do

			listener.getLogger().println("Publishing changes to Reviewboard.");

			author = entry.getAuthor().getId();
			files = entry.getAffectedPaths();
			changeDescr = entry.getMsg();

			// If the change description includes the override flag to force skipping the creation/update of a Review Request...
			ActionOverrideFlag override = this.parseDescriptionForOverride(changeDescr, externalID, build);
			if(override == ActionOverrideFlag.RB_SKIP){
				if(!this.skipUnflaggedChanges)
					listener.getLogger().println("Skipping Reviewboard Review Request create/update at the request of the change author.\nChange Description: " + changeDescr);
				else
					listener.getLogger().println("Skipping Reviewboard Review Request create/update. No action override was specified in change description.");
				
				continue;
			}

			// If the change description does not include the override flag to force the creation of a new Review Request,
			// search previous builds looking for a build that has previously been associated with a matching external ID and return it's reviewboard ID.
			// If a match is found, we'll simply update that review with the new changes instead of creating a new one.    				
			if(override == ActionOverrideFlag.RB_NEW){
				if(!this.skipUnflaggedChanges)
					listener.getLogger().println("Forcing the creation of a new Reviewboard Review Request at the request of the change author.\nChange Description: " + changeDescr);
				else
					listener.getLogger().println("Creating a new Reviewboard Review Request at the request of the change author.\nChange Description: " + changeDescr);
			}else{
				existingReviewBoardID = searchForPreviouslyCreatedReviewByExternalID(build, externalID);
				
				if(existingReviewBoardID != null){
					if(override != ActionOverrideFlag.RB_UPDATE && this.forceUpdateOverride && this.skipUnflaggedChanges){
						listener.getLogger().println("Changes were detected against an existing review, but ignored because description didn't explicitly include RB_UPDATE: " + existingReviewBoardID + "\nChange Description: " + changeDescr);
						continue;
					}
					listener.getLogger().println("Updating an existing Reviewboard Review Request with ID: " + existingReviewBoardID + "\nChange Description: " + changeDescr);
				}else{
					if(override == ActionOverrideFlag.RB_UPDATE)
						listener.getLogger().println("Change author requested an update to existing Review Request, but no previous build contained an External ID matching \"" + externalID + "\".  A new one will be created instead.\nChange Description: " + changeDescr);
					else
						listener.getLogger().println("Creating a new Reviewboard Review Request.\nChange Description: " + changeDescr);
				}
			}
			
			// If the SCM is Perforce, grab the changelistID.
			// Right now, only Perforce is supported.
			try{
				if(entry instanceof PerforceChangeLogEntry){
					PerforceChangeLogEntry pEntry = (PerforceChangeLogEntry)entry;
					changeListID = new Long(pEntry.getChange().getChangeNumber());
				}
			}catch(Exception e){
				e.printStackTrace();
			}
    		
			try {
				// We either have a new or an updated change to commit to reviewboard...
				ReviewInfoAction reviewInfo = submitChangeToReviewBoard(changeListID, externalID, existingReviewBoardID, author, changeDescr, files, build, launcher, listener);
				
				// If we were able to save it to reviewboard, save the info so we can look it back up on subsequent builds...
				if(reviewInfo != null){
					build.addAction(reviewInfo);
					if(existingReviewBoardID != null)
						listener.getLogger().println("Review " + existingReviewBoardID + " updated with changes from changelist: " + reviewInfo.getReviewRequest().getChangeListID());
					else
						listener.getLogger().println("Review " + reviewInfo.getReviewRequest().getReviewBoardID() + " created from changelist: " + reviewInfo.getReviewRequest().getChangeListID());
				}
				else
					throw new RuntimeException("Unable to create or update review request.  May be due to other exceptions during save to Reviewboard.");
			} catch (IOException e) {
				// If we got here, something bad happened.
				listener.getLogger().println(e.getMessage());
				e.printStackTrace(listener.getLogger());
			}
		}

        return true;
    }
    
    /**
     * Parses an override flag from the string.  If no flag is found and the RB_SKIP action
     * override is enabled through {@link #defaultActionOverrideSkip}, then RB_SKIP will be
     * returned, otherwise RB_NONE is returned.
     * 
     * @param descr String to parse for an override flag
     * @return override flag found in string, or if none is found, RB_SKIP or RB_NONE depending upon the state of defaultActionOverrideSkip
     */
    private ActionOverrideFlag parseDescriptionForOverride(String descr, String externalID, Run build){
    	
    	boolean explicitOverride = false;
    	ActionOverrideFlag override = (this.skipUnflaggedChanges) ? ActionOverrideFlag.RB_SKIP : ActionOverrideFlag.RB_NONE;
    	
    	if(descr == null || descr.isEmpty())
    		return override;
    	
    	Long existingReviewBoardID = searchForPreviouslyCreatedReviewByExternalID(build, externalID);

    	descr = descr.toUpperCase();
    	
    	ActionOverrideFlag flags[] = ActionOverrideFlag.values();
    	for(ActionOverrideFlag flag : flags){
    		if(descr.contains(flag.toString())){
    			override = flag;
    			explicitOverride = true;
    			break;
    		}
    	}

    	if(!explicitOverride && existingReviewBoardID != null && override == ActionOverrideFlag.RB_SKIP && !this.getForceUpdateOverride())
    		override = ActionOverrideFlag.RB_UPDATE;

    	return override;
    }
    
    /**
     * Recursively searches through previous builds looking for a prior change that has an
     * external ID that matches the supplied externalID parameter.  If one is found, then
     * that review has already been created in Reviewboard and it needs to be updated.
     * If no matching ID is found, a new review will be created.
     * 
     * @param run execution to inspect for an external ID
     * @param externalID external ID to match against a prior build's external ID
     * @return Reviewboard ID where supplied external ID matches a previously submit change's external ID, or null if not found
     */
    private Long searchForPreviouslyCreatedReviewByExternalID(Run run, String externalID){

    	if(run == null || externalID == null)
    		return null;
    	
    	Long priorExternalID = null;
    	
    	// A build may have multiple ReviewInfoActions if multiple changelists were submit and picked up by the build
    	List<ReviewInfoAction> actions = run.getActions(ReviewInfoAction.class);
    	for(ReviewInfoAction reviewInfo: actions){
	    	if(reviewInfo.equalsExternalID(externalID)){
	    		// Check to see if the last time we touched this review request was beyond the "stale" parameter
	    		// If it's not stale, we'll update the existing review request.  If it is stale, we'll create a new one.
	    		Long runDate = run.getTime().getTime();
	    		Long staleDate = runDate + (this.getDaysBeforeStaleReview() * 86400000);
	    		Long now = new Date().getTime();
	    		if(this.getDaysBeforeStaleReview() == -1 || now < staleDate)
	    			priorExternalID = reviewInfo.getReviewRequest().getReviewBoardID();
	    		
	    		break;
	    	}
    	}
    	
    	// If this build didn't match the external ID, go back further to the next build and check, and so on...
    	if(priorExternalID == null)
    		return searchForPreviouslyCreatedReviewByExternalID(run.getPreviousBuild(), externalID);
    	else
    		return priorExternalID;
    }
    
    /**
     * Creates a new (or updates an existing) review request in Reviewboard.
     * 
     * @param changeListID ID of current changelist to send to reviewboard
     * @param externalID arbitrary ID of an external source to map to reviewboard
     * @param reviewBoardID reviewboard ID of an existing review request, if one exists (may be null)
     * @param author author of the current changelist
     * @param files files included in the change (only required if reviewBoardID is not null)
     * @param build current build
     * @param launcher launcher to execute external processes
     * @param listener listener to handle build events
     * @return ReviewInfoAction if one was created.
     * @throws IOException
     */
    private ReviewInfoAction submitChangeToReviewBoard(Long changeListID, String externalID, Long reviewBoardID, String author, String changeDescr, Collection<String> files, AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException{
    	
		if(externalID == null || externalID.isEmpty())
			throw new IllegalArgumentException ("External ID annot be null or empty.");
		
		//if(changeListID == null || changeListID <= 0)
			//throw new IllegalArgumentException ("Changelist ID cannot be null or <= 0.");

		if(author == null || author.isEmpty())
			throw new IllegalArgumentException ("Author annot be null or empty.");

    	if(launcher == null)
    		throw new IllegalArgumentException("Launcher cannot be null, cannot execute post-review command without it.");

    	if(reviewBoardID != null && (files == null || files.isEmpty()))
    		throw new IllegalArgumentException("A previous review ID (" + reviewBoardID + ") was found for this external ID (" + externalID + "), but no files were supplied to update it with.");
    	
    	ReviewInfoAction reviewInfo = null;
    	boolean newReview = (reviewBoardID == null);
    	
    	// Used to read and write to the external process
    	Long reviewIDInError = 0L;
    	BufferedReader reader = null;
    	BufferedWriter writer = null;
    	try{
    		
    		// Allows us to read the response from the external process
    		// hudsonOut->p4in->reader
    		HudsonPipedOutputStream hudsonOut = new HudsonPipedOutputStream();
    		PipedInputStream p4in = new PipedInputStream(hudsonOut);
    		reader = new BufferedReader(new InputStreamReader(p4in));
    		
    		// hudsonIn<-p4Out<-writer
    		PipedInputStream hudsonIn = new PipedInputStream();
    		PipedOutputStream p4out = new PipedOutputStream(hudsonIn);
    		writer = new BufferedWriter(new OutputStreamWriter(p4out));

    		// Builds the reviewboard post-review command that will be executed.  It will generate either a new or update review request command line.
			ArgumentListBuilder cmd = this.buildCommandLine(changeListID, author, reviewBoardID, files, build);
			
			// Execute the external process
			// TODO: Add in some timer to kill this process if it hangs indefinitely.  This can happen (and did recently)
			//       when the password to Reviewboard changed for the post-review user.  post-review blocks at stdin waiting
			//       for a correct username and password.
    		Proc process = launcher.launch().cmds(cmd).envs(EnvVars.masterEnvVars).stdout(hudsonOut).stdin(hudsonIn).start();

    		// Parse the output from post-review for the ID number of the new or updated review request
			String response;
			try{
				while((response = reader.readLine()) != null){
	
					listener.getLogger().println(">> " + response);
	
					if( (reviewBoardID = matchStringToPattern(Long.class, response, reviewBoardIDRegExPattern, 1)) != null) { break; }
					if( (reviewIDInError = matchStringToPattern(Long.class, response, reviewBoardHTTPErrorPattern, 1)) != null) { break; }
	
				}
			}catch(IOException e){
				// we'll throw an IOE when the sub-process ends and there's nothing more to read.
			}
			
    		// Wait for the process to complete and get the status code
    		int exitCode = process.join();

    		// Close the output stream
    		hudsonOut.closeOnProcess(process);
    		
    		// 0 == successful execution
    		if(exitCode == 0){
    			listener.getLogger().println("Successfully executed post-review command");

   				if(reviewBoardID != null){
   					reviewInfo = new ReviewInfoAction(externalID, changeListID, reviewBoardID, author, changeDescr);
   				}
    			
				if(reviewInfo == null)
					throw new RuntimeException("Unable to create review info artifact.  The review request should still have been submit, but subsequent updates to this changelist will result in new review requests instead of updating this one.");
    		}else{
    			listener.getLogger().println("Failed executing post-review command.");

    			// If we had a review request ID to update, but this failed, it may have been
    			// deleted or otherwise unavailable, so try to create a new review request
    			// with the information for the changelist.  (The re-attempt is done outside of 
    			// this to let handles all close properly.
				if(reviewBoardID != null && reviewIDInError != null && reviewIDInError.equals(reviewBoardID)){
					listener.getLogger().println("Attempting to save a new review request.");
				}
    		}

		} catch (IOException e) {
			e.printStackTrace(listener.getLogger());
		} catch (InterruptedException e) {
			e.printStackTrace(listener.getLogger());
		} finally {
			if(reader != null)
				reader.close();
			if(writer != null)
				writer.close();
		}

		// We have created or updated a review request, so we need to do a few extra things to it.
		if(reviewInfo != null){
			
			// If this is a new request, set the default values on the request (reviewers, groups, bugs, etc)
			if(newReview){
				this.getDescriptor().getReviewboardAPI().setReviewers(reviewInfo.getReviewRequest(), this.getAllReviewers(reviewInfo));
				this.getDescriptor().getReviewboardAPI().setBugs(reviewInfo.getReviewRequest(), reviewInfo.getExternalID());
				this.getDescriptor().getReviewboardAPI().setGroups(reviewInfo.getReviewRequest(), this.defaultReviewGroups);
			// If this is an existing request, add the change list description to the diff of the review request
			}else
				this.getDescriptor().getReviewboardAPI().setChangeDescription(reviewInfo.getReviewRequest(), this.buildReviewboardChangeDescription(reviewInfo));
			
			// Publish the review if enabled.. this will send emails if Reviewboard is configured so
			if(this.publishReviews)
				this.getDescriptor().getReviewboardAPI().publishReview(reviewInfo.getReviewRequest());
			
			listener.getLogger().println("Successfully " + ((newReview)?"created":"updated") + " review request #" + reviewInfo.getReviewRequest().getReviewBoardID());
		} else if(reviewIDInError != null && reviewIDInError > 0){
			// If we had an error attempting to update an existing review, attempt to submit a new one
			// TODO: Make this an option
			listener.getLogger().println("Attempting to recover from failed submission to Reviewboard by creating a new Review Request...");
			reviewInfo = this.submitChangeToReviewBoard(changeListID, externalID, null, author, changeDescr, files, build, launcher, listener);
		} else {
			listener.getLogger().println("Unable to " + ((newReview)?"create":"update") + " a review request.");
		}
		
		return reviewInfo;
    }
    
    /**
     * Builds the change description that will show up on updated review requests.
     * The result contains the new change ID and the description supplied in the change.
     * The review request being updated must be in a draft state.
     * 
     * @param review Review to update.
     * @return String containing the change ID and description
     */
    private String buildReviewboardChangeDescription(ReviewInfoAction review){
    
    	if(review == null)
    		throw new IllegalArgumentException("Review information parameter was null.");
    	
    	return "Changelist ID: " + review.getReviewRequest().getChangeListID() + "\n\n" +
    	       "Description: " + review.getReviewRequest().getChangeDescription();
    }
    
    private String getAllReviewers(ReviewInfoAction review){
    	String reviewers = (this.authorAsReviewer) ? review.getReviewRequest().getAuthor() : "";
    	
    	if(this.defaultReviewers != null && !this.defaultReviewers.isEmpty())
    		reviewers +=  ( (reviewers.length() > 0) ? "," : "" ) + this.defaultReviewers;

    	return reviewers;
    }
    
    /**
     * Attempts to match a pattern against a string and return the pattern group 
     * supplied.  This is useful for extracting a piece of information from a
     * predictable string, such as retrieving the external ID from a change description,
     * the Review ID from a new review request submission, etc.  This means that the
     * developer must know the string (str) being passed in, the pattern to match the
     * string against, the format of the value being extracted (String, Long, Integer, etc),
     * and which pattern group to return (zero being the entire string and >1 being regular 
     * expression groups defined in the pattern).
     * 
     * @param <T> Type of the returnType
     * @param returnType Type to return
     * @param str String to match the pattern against
     * @param pattern Pattern to match to the string
     * @param patternGroupNumber Regular expression group to extract
     * @return Value of the group extracted, typed according to the generic type parameter supplied, or null if no match is made or an error occurs
     */
    private <T> T matchStringToPattern(Class<T> returnType, String str,
			Pattern pattern, int patternGroupNumber) {
		
    	Constructor<T> constructor;
    	T patternGroup = null;

    	try {
			if(!str.trim().isEmpty()){
			
				constructor = returnType.getDeclaredConstructor(String.class);

				// Match the current line of the output against the pattern
				Matcher match = pattern.matcher(str);
				match.lookingAt();
				if(match.groupCount() > patternGroupNumber-1){
					patternGroup = constructor.newInstance(match.group(patternGroupNumber));
				}
			}
		}catch(IllegalStateException ise) {
		}catch(PatternSyntaxException pse){
			pse.printStackTrace();
		}catch(NumberFormatException nfe){
			// TODO: exception handling
			nfe.printStackTrace();
		} catch (IllegalArgumentException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InstantiationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SecurityException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return patternGroup;
	}

	/**
     * Builds the required commands to execute post-review given the parameters supplied.  The result will
     * either be a list of commands to create a new review, a list of commands to update an existing review
     * or an IllegalArgumentException will be thrown because we could not determine what path to create.
     * 
     * @param changeListID ID of change from the SCM
     * @param author author of the change in SCM
     * @param reviewBoardID existing reviewboard ID (may be null if new review request, but cannot be null if files is not null)
     * @param files files to update a review request with (cannot be null if reviewBoardID is null)
     * @return argument list that can be passed to Laucher
     */
    private ArgumentListBuilder buildCommandLine(Long changeListID, String author, Long reviewBoardID, Collection<String> files, AbstractBuild build){
    	
    	ArgumentListBuilder args = null;
    	
    	// Creates command arguments to update an existing review
    	if(reviewBoardID != null && author != null && files != null && !files.isEmpty())
    		args = buildCommandLineForExistingReview(author, reviewBoardID, files, build);
    	// Creates command arguments to create a new review request
    	else if(changeListID != null && author != null)
    		args = buildCommandLineForNewReview(changeListID, author, build);
    	else
    		throw new IllegalArgumentException("Unable to create command line for reviewboard.  Invalid options were supplied.");
    	
    	//System.out.println(">> " + args.toStringWithQuote());
    	
    	return args;
    }
    
    /**
     * Builds command arguments to create a new review request with post-review.
     * 
     * @param changeListID ID of change in the SCM
     * @param author author of the change
     * @return arguments to create a new review request
     */
    private ArgumentListBuilder buildCommandLineForNewReview(Long changeListID, String author, AbstractBuild build){
    	
    	ArgumentListBuilder args = new ArgumentListBuilder();
    	
		args.add(this.getDescriptor().getCmdPath());
		
	    // Commented out debugPostReview param currently because debug mode hangs Hudson due to leaking file handles
		/*
		if(debugPostReview)
			args.add("-d");
		*/
		
		args.add("--server=" + this.getDescriptor().getUrl());
		args.add("--username=" + this.getDescriptor().getUsername());
		args.add("--password=" + this.getDescriptor().getPassword());
		args.add("--submit-as=" + author);

		SCM scm = build.getProject().getScm();
		if(scm instanceof PerforceSCM)
			args.add("--p4-client=" + ((PerforceSCM)scm).getP4Client());
		
		args.add(changeListID);
    	
    	return args;
    	
    }
    
    /**
    /**
     * Builds command arguments to update an existing review request with post-review.
     * 
     * @param author author of the change
     * @param reviewBoardID ID of existing review request to update
     * @param files files from the changelist to update the review request with
     * @return arguments to create a new review request
     */
    private ArgumentListBuilder buildCommandLineForExistingReview(String author, Long reviewBoardID, Collection<String> files, AbstractBuild build){
    	
    	ArgumentListBuilder args = new ArgumentListBuilder();
    	
		args.add(this.getDescriptor().getCmdPath());

	    // Commented out debugPostReview param currently because debug mode hangs Hudson due to leaking file handles
		/*
		if(debugPostReview)
			args.add("-d");
		*/
		
		args.add("-r", reviewBoardID.toString());
		args.add("--server=" + this.getDescriptor().getUrl());
		args.add("--username=" + this.getDescriptor().getUsername());
		args.add("--password=" + this.getDescriptor().getPassword());
		args.add("--submit-as=" + author);
		
		SCM scm = build.getProject().getScm();
		if(scm instanceof PerforceSCM)
			args.add("--p4-client=" + ((PerforceSCM)scm).getP4Client());

		for(String file: files){
			args.add(file);
		}
		
    	return args;
    }
    
    /**
     * Inspects the supplied string for a matching pattern, that is configured from the Hudson Build Configuration
     * page, and returns it if it is found.  This external ID is often that from an bug tracking system such as 
     * JIRA, and this description is often pulled from the changelist description itself.  That means the individual
     * submitting the change needs to supply the external ID somewhere within the body of the change description.
     * 
     * @param descr to parse for key
     * @param logger to send messages to
     * @return
     */
    private String getExternalKeyFromChangeDescr(String descr, PrintStream logger){
    	
    	if(keyRegExPattern == null)
    		return "";
    	if(descr == null || descr.isEmpty())
    		return "";
    	
    	String externalKey = null;
    	
    	logger.println("Matching Pattern to ChangeListDescr: " + keyRegExPattern.pattern() + " -> " + descr);

    	try{
    		Matcher matcher = keyRegExPattern.matcher(descr.trim());
    		matcher.lookingAt();
    		if(matcher.groupCount() > 0){
    			externalKey = matcher.group(1);
    			logger.println("External key found in changelist description: " + externalKey);
    		}
    	}catch(IllegalStateException ise){
    		logger.println("No pattern matched in changelist description. \nPattern: " + keyRegExPattern.pattern() + "\nDescription: " + descr);
    	}
    	
    	return externalKey;
    }
}

