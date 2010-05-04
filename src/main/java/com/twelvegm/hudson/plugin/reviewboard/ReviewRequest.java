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

/**
 * Represents a single Review Request in Reviewboard.
 * 
 * Currently not a complete model of a review request.  Only contains values
 * needed for the Reviewboard Hudson Plugin.  This should be expanded as 
 * more fields are required.
 * 
 * @author Ryan Shelley 
 * @version 1.0-beta
 */
public class ReviewRequest {

	private Long changeListID;
	private Long reviewBoardID;
	private String author;
	private String changeDescription;
	
	/**
	 * Constructs a new Review Request object based upon values in Reviewboard.
	 * 
	 * @param changeListID ID from SCM associated with a builds external ID and review request ID
	 * @param reviewBoardID ID of review request in Reviewboard
	 * @param author author of change (usually the submitter of the changelist in SCM)
	 * @param changeDescription description of the change that was submit to SCM
	 */
	public ReviewRequest(final Long changeListID, final Long reviewBoardID, final String author, final String changeDescription) {

		//if(changeListID == null || changeListID <= 0)
		//	throw new IllegalArgumentException ("Changelist ID cannot be null or <= 0.");

		if(reviewBoardID == null || reviewBoardID <= 0)
			throw new IllegalArgumentException ("ReviewBoard ID cannot be null or <= 0.");
		
		if(author == null || author.isEmpty())
			throw new IllegalArgumentException ("Author annot be null or empty.");
		
		this.changeListID = changeListID;
		this.reviewBoardID = reviewBoardID;
		this.author = author;
		this.changeDescription = changeDescription;
	}
	
	/**
	 * Returns the changelist ID associated with a review request.
	 * This value is often the changelist ID from SCM.
	 * 
	 * @return changelist ID from Reviewboard
	 */
	public Long getChangeListID() {
		return changeListID;
	}
	
	/**
	 * Sets the changelist ID associated with a review request
	 * This value is often the changelist ID from SCM.
	 * 
	 * @param changeListID changelist ID from Reviewboard
	 */
	public void setChangeListID(Long changeListID) {
		this.changeListID = changeListID;
	}
	
	/**
	 * Returns the reviewboard ID associated with the review request
	 * 
	 * @return reviewboard ID from reviewboard
	 */
	public Long getReviewBoardID() {
		return reviewBoardID;
	}
	
	/**
	 * Sets the Reviewboard ID that maps this object into a Reviewboard review request.
	 * 
	 * @param reviewBoardID Reviewboard ID of matching review request
	 */
	public void setReviewBoardID(Long reviewBoardID) {
		this.reviewBoardID = reviewBoardID;
	}
	
	/**
	 * Author of changelist from Reviewboard. This value is often
	 * the author of the changelist from SCM.
	 * 
	 * @return author of changelist from Reviewboard
	 */
	public String getAuthor() {
		return author;
	}
	
	/**
	 * Sets the author of the changelist from Reviewboard. This value is often
	 * the author of the changelist from SCM.
	 * 
	 * @param author author of the changelist from Reviewboard
	 */
	public void setAuthor(String author) {
		this.author = author;
	}
	
	/**
	 * Description of change from Reviewboard. This value is often
	 * the change description of the changelist from SCM.
	 * 
	 * @return description of change from Reviewboard
	 */
	public String getChangeDescription() {
		return changeDescription;
	}
	
	/**
	 * Sets the change description value for this revie request. This value is often
	 * the change description of the changelist from SCM.
	 * 
	 * @param changeDescription
	 */
	public void setChangeDescription(String changeDescription) {
		this.changeDescription = changeDescription;
	}
}
