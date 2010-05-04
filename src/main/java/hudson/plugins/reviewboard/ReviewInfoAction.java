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

import com.twelvegm.hudson.plugin.reviewboard.ReviewRequest;

import hudson.model.InvisibleAction;

public class ReviewInfoAction extends InvisibleAction {

	private final String externalID;
	private final ReviewRequest reviewRequest;
	
	/**
	 * Contains a mapping between an external ID (such as JIRA) and Reviewboard so that
	 * multiple changes can update existing review requsts instead of forcing the creation
	 * of new requests each change list pulled from the SCM.  A build may have multiple
	 * ReviewInfoAction objects associated with it as multiple changelists may be captured
	 * during a build process, but each ReviewInfoAction maps to a single changelist,
	 * reviewboard ID, and external ID
	 * 
	 * @param externalID ID from an external repository, such as JIRA
	 * @param changeListID ID from SCM associated with a builds external ID and review request ID
	 * @param reviewBoardID ID of review request in Reviewboard
	 * @param author author of change (usually the submitter of the changelist in SCM)
	 * @param changeDescription description of the change that was submit to SCM
	 */
	public ReviewInfoAction(final String externalID, final Long changeListID, final Long reviewBoardID, final String author, final String changeDescription) {
		
		if(externalID == null || externalID.isEmpty())
			throw new IllegalArgumentException ("External ID annot be null or empty.");
		
		//if(changeListID == null || changeListID <= 0)
		//	throw new IllegalArgumentException ("Changelist ID cannot be null or <= 0.");

		if(reviewBoardID == null || reviewBoardID <= 0)
			throw new IllegalArgumentException ("ReviewBoard ID cannot be null or <= 0.");
		
		if(author == null || author.isEmpty())
			throw new IllegalArgumentException ("Author annot be null or empty.");
		
		this.externalID = externalID;
		this.reviewRequest = new ReviewRequest(changeListID, reviewBoardID, author, changeDescription);
	}
	
	/**
	 * Returns the external ID associated with a build
	 * 
	 * @return external ID (such as JIRA ID)
	 */
	public String getExternalID() {
		return externalID;
	}
	
	/**
	 * Returns the review request associated with Reviewboard
	 * 
	 * @return review request
	 */
	public ReviewRequest getReviewRequest() {
		return reviewRequest;
	}
	
	/**
	 * Compares a supplied external ID to this object's external ID to 
	 * determine if they match.  If they match then a review request already
	 * exists mapping this external ID to reviewboard.  This will lead to
	 * the review request being updated instead of a new review being created.
	 * 
	 * @param externalID external ID to compare against this external ID
	 * @return true if they match (case insensitive), false otherwise
	 */
	protected boolean equalsExternalID(String externalID){
		return (this.externalID != null && (this.externalID.equalsIgnoreCase(externalID)));
	}
}
