/*
 * Copyright (C) 2017 - present  Instructure, Inc.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.instructure.teacher.fragments

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.instructure.canvasapi2.models.*
import com.instructure.canvasapi2.models.postmodels.PendingSubmissionComment
import com.instructure.canvasapi2.utils.Pronouns
import com.instructure.pandautils.fragments.BaseListFragment
import com.instructure.pandautils.services.NotoriousUploadService
import com.instructure.pandautils.utils.*
import com.instructure.teacher.R
import com.instructure.teacher.adapters.SpeedGraderCommentsAdapter
import com.instructure.teacher.decorations.SpacesItemDecoration
import com.instructure.teacher.dialog.SGAddMediaCommentDialog
import com.instructure.teacher.events.SubmissionCommentsUpdated
import com.instructure.teacher.events.UploadMediaCommentUpdateEvent
import com.instructure.teacher.events.post
import com.instructure.teacher.factory.SpeedGraderCommentsPresenterFactory
import com.instructure.teacher.holders.SpeedGraderCommentHolder
import com.instructure.teacher.models.SubmissionCommentWrapper
import com.instructure.teacher.presenters.SpeedGraderCommentsPresenter
import com.instructure.teacher.utils.RecyclerViewUtils
import com.instructure.teacher.utils.getColorCompat
import com.instructure.teacher.utils.view
import com.instructure.teacher.view.CommentTextFocusedEvent
import com.instructure.teacher.view.MediaCommentDialogClosedEvent
import com.instructure.teacher.view.UploadMediaCommentEvent
import com.instructure.teacher.viewinterface.SpeedGraderCommentsView
import kotlinx.android.synthetic.main.adapter_submission_comment.*
import kotlinx.android.synthetic.main.fragment_speedgrader_comments.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import java.io.File


class SpeedGraderCommentsFragment : BaseListFragment<SubmissionCommentWrapper, SpeedGraderCommentsPresenter, SpeedGraderCommentsView, SpeedGraderCommentHolder, SpeedGraderCommentsAdapter>(), SpeedGraderCommentsView {
    var mRawComments by ParcelableArrayListArg<SubmissionComment>()
    var mSubmissionId by LongArg()
    var mSubmissionHistory by ParcelableArrayListArg<Submission>()
    var mAssignee by ParcelableArg<Assignee>(StudentAssignee(User()))
    var mCourseId by LongArg()
    var mAssignmentId by LongArg()
    var mIsGroupMessage by BooleanArg()
    var mGradeAnonymously by BooleanArg()

    private val mCommentsAdapter by lazy { SpeedGraderCommentsAdapter(requireContext(), presenter, mCourseId, presenter.assignee, mGradeAnonymously, onAttachmentClicked) }
    private val mLayoutManager by lazy { LinearLayoutManager(requireContext())
        .apply { this.stackFromEnd = true }}

    override fun layoutResId() = R.layout.fragment_speedgrader_comments
    override fun getList() = presenter.data
    override fun getAdapter() = mCommentsAdapter
    override fun getRecyclerView(): RecyclerView = speedGraderCommentsRecyclerView
    override fun getPresenterFactory() = SpeedGraderCommentsPresenterFactory(mRawComments, mSubmissionHistory, mAssignee, mCourseId, mAssignmentId, mIsGroupMessage)
    override fun onCreateView(view: View?) {}
    override fun onRefreshStarted() {}
    override fun onRefreshFinished() {}

    private val onAttachmentClicked = { attachment: Attachment -> attachment.view(requireContext()) }

    override fun onPresenterPrepared(presenter: SpeedGraderCommentsPresenter) {
        RecyclerViewUtils.buildRecyclerView(requireContext(), adapter, presenter, swipeRefreshLayout, speedGraderCommentsRecyclerView, speedGraderCommentsEmptyView, getString(R.string.no_submission_comments))
        speedGraderCommentsRecyclerView.addItemDecoration(SpacesItemDecoration(requireContext(), R.dimen.speedgrader_comment_margins))
        speedGraderCommentsRecyclerView.layoutManager = mLayoutManager
        swipeRefreshLayout.isEnabled = false
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onReadySetGo(presenter: SpeedGraderCommentsPresenter) {
        setupCommentInput()
        presenter.loadData(false)

        // Check for any media comment updates
        val event = EventBus.getDefault().getStickyEvent(UploadMediaCommentUpdateEvent::class.java)
        event?.get {
            checkMediaCommentsAndUpdate(it)
        }
    }

    private fun setupCommentInput() {
        sendCommentButton.imageTintList = ViewStyler.generateColorStateList(
                intArrayOf(-android.R.attr.state_enabled) to requireContext().getColorCompat(R.color.defaultTextGray),
                intArrayOf() to ThemePrefs.buttonColor
        )
        if (!mGradeAnonymously) commentEditText.hint = Pronouns.resource(
            requireContext(),
            R.string.sendMessageToHint,
            mAssignee.pronouns,
            Pronouns.span(mAssignee.name, mAssignee.pronouns)
        )
        sendCommentButton.isEnabled = false
        sendCommentButton.setGone()
        commentEditText.onTextChanged {
            sendCommentButton.isEnabled = it.isNotBlank()
            sendCommentButton.setVisible(it.isNotBlank())
        }
        sendCommentButton.onClickWithRequireNetwork {
            presenter.sendComment(commentEditText.text.toString())
            errorLayout?.announceForAccessibility(getString(R.string.sendingSimple))
        }

        commentEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                EventBus.getDefault().post(CommentTextFocusedEvent(mAssignee.id))
            }
        }

        addMediaAttachment.onClick {
            SGAddMediaCommentDialog.show(requireActivity().supportFragmentManager,
                    presenter.assignmentId, presenter.courseId,
                    when (presenter.assignee) {
                        is StudentAssignee -> (presenter.assignee as StudentAssignee).student.id
                        is GroupAssignee -> (presenter.assignee as GroupAssignee).students.firstOrNull()?.id ?: presenter.assignee.id
                    }, when (presenter.assignee) {
                is GroupAssignee -> true
                else -> false
            })

            addMediaAttachment.isEnabled = false
        }
    }

    override fun scrollToBottom() {
        mLayoutManager.scrollToPosition(presenter.data.size() - 1)
    }

    override fun setDraftText(comment: String?) {
        commentEditText.setText(comment.orEmpty())
    }

    override fun onStop() {
        if (EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().unregister(this)

        presenter.saveDraft(commentEditText.text.toString())
        super.onStop()
    }

    override fun checkIfEmpty() {
         RecyclerViewUtils.checkIfEmpty(speedGraderCommentsEmptyView, speedGraderCommentsRecyclerView, swipeRefreshLayout, adapter, presenter.isEmpty)
    }

    @Suppress("unused")
    @Subscribe
    fun onUploadMediaComment(event: UploadMediaCommentEvent) {
        if (mAssignee.id == event.assigneeId) {
            presenter.createPendingMediaComment(event.file.absolutePath)
            uploadSGMediaComment(event.file, event.assignmentId, event.courseId)
            addMediaAttachment.isEnabled = true
        }
    }

    @Suppress("UNUSED_PARAMETER", "unused")
    @Subscribe
    fun onMediaCommentDialogClosed(event: MediaCommentDialogClosedEvent) {
        addMediaAttachment.isEnabled = true
    }

    @Suppress("unused")
    @Subscribe
    fun onUploadMediaCommentSuccess(event: UploadMediaCommentUpdateEvent) {
        event.get {
            checkMediaCommentsAndUpdate(it)
        }
    }

    private fun checkMediaCommentsAndUpdate(comments: MutableMap<String, MutableList<Pair<PendingSubmissionComment, SubmissionComment?>>>) {
        if (comments.containsKey(presenter.mPageId)) {
            presenter.updatePendingComments(comments[presenter.mPageId]!!)

            SubmissionCommentsUpdated().post()

            // Update the event
            EventBus.getDefault().getStickyEvent(UploadMediaCommentUpdateEvent::class.java)?.get {
                comments.remove(presenter.mPageId)
                UploadMediaCommentUpdateEvent(comments).post()
            }
        }
    }

    /**
     * Takes an Audio/Video file and uploads it using the NotoriousUploadService
     *
     * @param mediaFile File pointing to the media to upload
     */
    private fun uploadSGMediaComment(mediaFile: File, assignmentId: Long, courseID: Long) {
        val mediaUri = Uri.fromFile(mediaFile)

        val serviceIntent = Intent(requireActivity(), NotoriousUploadService::class.java)
        with(serviceIntent) {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Const.MEDIA_FILE_PATH, mediaUri.path)
            putExtra(Const.ACTION, NotoriousUploadService.ACTION.SUBMISSION_COMMENT)
            putExtra(Const.ASSIGNMENT, Assignment(id = assignmentId, courseId = courseID))
            putExtra(Const.STUDENT_ID, mAssignee.id)
            putExtra(Const.IS_GROUP, mAssignee is GroupAssignee)
            putExtra(Const.PAGE_ID, presenter.mPageId)
        }

        ContextCompat.startForegroundService(requireActivity(), serviceIntent)
    }

    companion object {
        fun newInstance(
                submission: Submission?,
                assignee: Assignee,
                courseId: Long,
                assignmentId: Long,
                isGroupMessage: Boolean,
                gradeAnonymously: Boolean
        ) = SpeedGraderCommentsFragment().apply {
            mRawComments = ArrayList(submission?.submissionComments ?: emptyList())
            mSubmissionId = submission?.id ?: -1
            mSubmissionHistory = ArrayList(submission?.submissionHistory?.filterNotNull()?.filter { it.submissionType != null && it.workflowState != "unsubmitted" } ?: emptyList())
            mAssignee = assignee
            mCourseId = courseId
            mAssignmentId = assignmentId
            mIsGroupMessage = isGroupMessage
            mGradeAnonymously = gradeAnonymously
        }
    }

}

