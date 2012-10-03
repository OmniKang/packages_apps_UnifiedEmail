/*******************************************************************************
 *      Copyright (C) 2012 Google Inc.
 *      Licensed to The Android Open Source Project.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *******************************************************************************/

package com.android.mail.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;

import com.android.mail.R;
import com.android.mail.ui.ViewMode.ModeChangeListener;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.google.common.annotations.VisibleForTesting;

/**
 * This is a custom layout that manages the possible views of Gmail's large screen (read: tablet)
 * activity, and the transitions between them.
 *
 * This is not intended to be a generic layout; it is specific to the {@code Fragment}s
 * available in {@link MailActivity} and assumes their existence. It merely configures them
 * according to the specific <i>modes</i> the {@link Activity} can be in.
 *
 * Currently, the layout differs in three dimensions: orientation, two aspects of view modes.
 * This results in essentially three states: One where the folders are on the left and conversation
 * list is on the right, and two states where the conversation list is on the left: one in which
 * it's collapsed and another where it is not.
 *
 * In folder or conversation list view, conversations are hidden and folders and conversation lists
 * are visible. This is the case in both portrait and landscape
 *
 * In Conversation List or Conversation View, folders are hidden, and conversation lists and
 * conversation view is visible. This is the case in both portrait and landscape.
 *
 * In the Gmail source code, this was called TriStateSplitLayout
 */
final class TwoPaneLayout extends FrameLayout implements ModeChangeListener {

    private static final String LOG_TAG = "TwoPaneLayout";
    private static final long SLIDE_DURATION_MS = 300;

    private final double mConversationListWeight;
    private final double mFolderListWeight;
    private final TimeInterpolator mSlideInterpolator;
    /**
     * True if and only if the conversation list is collapsible in the current device configuration.
     * See {@link #isConversationListCollapsed()} to see whether it is currently collapsed
     * (based on the current view mode).
     */
    private final boolean mListCollapsible;

    /**
     * The current mode that the tablet layout is in. This is a constant integer that holds values
     * that are {@link ViewMode} constants like {@link ViewMode#CONVERSATION}.
     */
    private int mCurrentMode = ViewMode.UNKNOWN;
    /**
     * This mode represents the current positions of the three panes. This is split out from the
     * current mode to give context to state transitions.
     */
    private int mPositionedMode = ViewMode.UNKNOWN;

    private AbstractActivityController mController;
    private LayoutListener mListener;
    private boolean mIsSearchResult;

    private View mConversationView;
    private View mFoldersView;
    private View mListView;
    /**
     * A special view used during animation of the conversation list.
     * <p>
     * The conversation list changes width when switching view modes, so to visually smooth out
     * the transition, we cross-fade the old and new widths. During the transition, a bitmap of the
     * old conversation list is kept here, and this view moves in tandem with the real list view,
     * but its opacity gradually fades out to give way to the new width.
     */
    private ConversationListCopy mListCopyView;

    /**
     * During a mode transition, this value is the final width for {@link #mListCopyView}. We want
     * to avoid changing its width during the animation, as it should match the initial width of
     * {@link #mListView}.
     */
    private Integer mListCopyWidthOnComplete;

    public TwoPaneLayout(Context context) {
        this(context, null);
    }

    public TwoPaneLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        final Resources res = getResources();

        // The conversation list might be visible now, depending on the layout: in portrait we
        // don't show the conversation list, but in landscape we do.  This information is stored
        // in the constants
        mListCollapsible = res.getBoolean(R.bool.list_collapsed);

        mSlideInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.decelerate_cubic);

        final int folderListWeight = res.getInteger(R.integer.folder_list_weight);
        final int convListWeight = res.getInteger(R.integer.conversation_list_weight);
        final int convViewWeight = res.getInteger(R.integer.conversation_view_weight);
        mFolderListWeight = (double) folderListWeight
                / (folderListWeight + convListWeight);
        mConversationListWeight = (double) convListWeight
                / (convListWeight + convViewWeight);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mFoldersView = findViewById(R.id.content_pane);
        mListView = findViewById(R.id.conversation_list_pane);
        mListCopyView = (ConversationListCopy) findViewById(R.id.conversation_list_copy);
        mConversationView = findViewById(R.id.conversation_pane);

        // all panes start GONE in initial UNKNOWN mode to avoid drawing misplaced panes
        mCurrentMode = ViewMode.UNKNOWN;
        mFoldersView.setVisibility(GONE);
        mListView.setVisibility(GONE);
        mListCopyView.setVisibility(GONE);
        mConversationView.setVisibility(GONE);
    }

    @VisibleForTesting
    public void setController(AbstractActivityController controller, boolean isSearchResult) {
        mController = controller;
        mListener = controller;
        mIsSearchResult = isSearchResult;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        LogUtils.d(Utils.VIEW_DEBUGGING_TAG, "TPL(%s).onMeasure()", this);
        setupPaneWidths(MeasureSpec.getSize(widthMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        LogUtils.d(Utils.VIEW_DEBUGGING_TAG, "TPL(%s).onLayout()", this);
        if (changed || mCurrentMode != mPositionedMode) {
            positionPanes(getMeasuredWidth());
        }
        super.onLayout(changed, l, t, r, b);
    }

    /**
     * Sizes up the three sliding panes. This method will ensure that the LayoutParams of the panes
     * have the correct widths set for the current overall size and view mode.
     *
     * @param parentWidth this view's new width
     */
    private void setupPaneWidths(int parentWidth) {
        final int foldersWidth = computeFolderListWidth(parentWidth);
        final int convWidth = computeConversationWidth(parentWidth);

        // only adjust the fixed folder and conversation view widths when my width changes
        if (parentWidth != getMeasuredWidth()) {
            LogUtils.i(LOG_TAG, "setting up new TPL, w=%d fw=%d cv=%d", parentWidth,
                    foldersWidth, convWidth);

            setPaneWidth(mFoldersView, foldersWidth);
            setPaneWidth(mConversationView, convWidth);
        }

        final int currListWidth = getPaneWidth(mListView);
        int listWidth = currListWidth;
        switch (mCurrentMode) {
            case ViewMode.CONVERSATION:
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
                if (!mListCollapsible) {
                    listWidth = parentWidth - convWidth;
                }
                break;
            case ViewMode.CONVERSATION_LIST:
            case ViewMode.SEARCH_RESULTS_LIST:
                listWidth = parentWidth - foldersWidth;
                break;
            default:
                break;
        }
        LogUtils.d(LOG_TAG, "conversation list width change, w=%d", listWidth);
        setPaneWidth(mListView, listWidth);

        if ((mCurrentMode != mPositionedMode && mPositionedMode != ViewMode.UNKNOWN)
                || mListCopyWidthOnComplete != null) {
            mListCopyWidthOnComplete = listWidth;
        } else {
            setPaneWidth(mListCopyView, listWidth);
        }
    }

    /**
     * Positions the three sliding panes at the correct X offset (using {@link View#setX(float)}).
     * When switching from list->conversation mode or vice versa, animate the change in X.
     *
     * @param width
     */
    private void positionPanes(int width) {
        if (mPositionedMode == mCurrentMode) {
            return;
        }

        boolean hasPositions = false;
        int convX = 0, listX = 0, foldersX = 0;

        switch (mCurrentMode) {
            case ViewMode.CONVERSATION:
            case ViewMode.SEARCH_RESULTS_CONVERSATION: {
                final int foldersW = getPaneWidth(mFoldersView);
                final int listW;
                listW = getPaneWidth(mListView);

                if (mListCollapsible) {
                    convX = 0;
                    listX = -listW;
                    foldersX = listX - foldersW;
                } else {
                    convX = listW;
                    listX = 0;
                    foldersX = -foldersW;
                }
                hasPositions = true;
                LogUtils.i(LOG_TAG, "conversation mode layout, x=%d/%d/%d", foldersX, listX, convX);
                break;
            }
            case ViewMode.CONVERSATION_LIST:
            case ViewMode.SEARCH_RESULTS_LIST: {
                convX = width;
                listX = getPaneWidth(mFoldersView);
                foldersX = 0;

                hasPositions = true;
                LogUtils.i(LOG_TAG, "conv-list mode layout, x=%d/%d/%d", foldersX, listX, convX);
                break;
            }
            default:
                break;
        }

        if (hasPositions) {
            animatePanes(foldersX, listX, convX);
        }

        mPositionedMode = mCurrentMode;
    }

    /**
     * @param foldersX
     * @param listX
     * @param convX
     */
    private void animatePanes(int foldersX, int listX, int convX) {
        // If positioning has not yet happened, we don't need to animate panes into place.
        // This happens on first layout, rotate, and when jumping straight to a conversation from
        // a view intent.
        if (mPositionedMode == ViewMode.UNKNOWN) {
            mConversationView.setX(convX);
            mFoldersView.setX(foldersX);
            mListView.setX(listX);

            // listeners need to know that the "transition" is complete, even if one is not run.
            // defer notifying listeners because we're in a layout pass, and they might do layout.
            post(new Runnable() {
                @Override
                public void run() {
                    onTransitionComplete();
                }
            });
            return;
        }

        // freeze the current list view before it gets redrawn
        mListCopyView.bind(mListView);
        mListCopyView.setX(mListView.getX());

        mListCopyView.setAlpha(1.0f);
        mListView.setAlpha(0.0f);

        useHardwareLayer(true);

        mConversationView.animate().x(convX);
        mFoldersView.animate().x(foldersX);
        mListCopyView.animate().x(listX).alpha(0.0f);
        mListView.animate()
            .x(listX)
            .alpha(1.0f)
            .setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mListCopyView.unbind();
                    useHardwareLayer(false);
                    fixupListCopyWidth();
                    onTransitionComplete();
                }
                @Override
                public void onAnimationCancel(Animator animation) {
                    mListCopyView.unbind();
                    useHardwareLayer(false);
                }
        });
        configureAnimations(mConversationView, mFoldersView, mListView, mListCopyView);
    }

    private void configureAnimations(View... views) {
        for (View v : views) {
            v.animate()
                .setInterpolator(mSlideInterpolator)
                .setDuration(SLIDE_DURATION_MS);
        }
    }

    private void useHardwareLayer(boolean useHardware) {
        final int layerType = useHardware ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        mFoldersView.setLayerType(layerType, null);
        mListView.setLayerType(layerType, null);
        mListCopyView.setLayerType(layerType, null);
        mConversationView.setLayerType(layerType, null);
    }

    private void fixupListCopyWidth() {
        if (mListCopyWidthOnComplete == null ||
                getPaneWidth(mListCopyView) == mListCopyWidthOnComplete) {
            mListCopyWidthOnComplete = null;
            return;
        }
        LogUtils.i(LOG_TAG, "onAnimationEnd of list view, setting copy width to %d",
                mListCopyWidthOnComplete);
        setPaneWidth(mListCopyView, mListCopyWidthOnComplete);
        mListCopyWidthOnComplete = null;
    }

    private void onTransitionComplete() {
        if (mController.isDestroyed()) {
            // quit early if the hosting activity was destroyed before the animation finished
            LogUtils.i(LOG_TAG, "IN TPL.onTransitionComplete, activity destroyed->quitting early");
            return;
        }

        switch (mCurrentMode) {
            case ViewMode.CONVERSATION:
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
                dispatchConversationVisibilityChanged(true);
                dispatchConversationListVisibilityChange(!isConversationListCollapsed());

                break;
            case ViewMode.CONVERSATION_LIST:
            case ViewMode.SEARCH_RESULTS_LIST:
                dispatchConversationVisibilityChanged(false);
                dispatchConversationListVisibilityChange(true);

                break;
            default:
                break;
        }
    }

    /**
     * Computes the width of the conversation list in stable state of the current mode.
     */
    protected int computeConversationListWidth() {
        return computeConversationListWidth(getMeasuredWidth());
    }

    /**
     * Computes the width of the conversation list in stable state of the current mode.
     */
    private int computeConversationListWidth(int totalWidth) {
        switch (mCurrentMode) {
            case ViewMode.CONVERSATION_LIST:
            case ViewMode.SEARCH_RESULTS_LIST:
                return totalWidth - computeFolderListWidth(totalWidth);
            case ViewMode.CONVERSATION:
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
                return (int) (totalWidth * mConversationListWeight);
        }
        return 0;
    }

    /**
     * Computes the width of the conversation pane in stable state of the
     * current mode.
     */
    private int computeConversationWidth(int totalWidth) {
        if (mListCollapsible) {
            return totalWidth;
        } else {
            return totalWidth - (int) (totalWidth * mConversationListWeight);
        }
    }

    /**
     * Computes the width of the folder list in stable state of the current mode.
     */
    private int computeFolderListWidth(int parentWidth) {
        if (mIsSearchResult) {
            return 0;
        }
        return (int) (parentWidth * mFolderListWeight);
    }

    private void dispatchConversationListVisibilityChange(boolean visible) {
        if (mListener != null) {
            mListener.onConversationListVisibilityChanged(visible);
        }
    }

    private void dispatchConversationVisibilityChanged(boolean visible) {
        if (mListener != null) {
            mListener.onConversationVisibilityChanged(visible);
        }
    }

    private int getPaneWidth(View pane) {
        return pane.getLayoutParams().width;
    }

    public View getConversationView() {
        return mConversationView;
    }

    /**
     * @return Whether or not the conversation list is visible on screen.
     */
    public boolean isConversationListCollapsed() {
        return !ViewMode.isListMode(mCurrentMode) && mListCollapsible;
    }

    @Override
    public void onViewModeChanged(int newMode) {
        // make all initially GONE panes visible only when the view mode is first determined
        if (mCurrentMode == ViewMode.UNKNOWN) {
            mFoldersView.setVisibility(VISIBLE);
            mListView.setVisibility(VISIBLE);
            mListCopyView.setVisibility(VISIBLE);
            mConversationView.setVisibility(VISIBLE);
        }

        // detach the pager immediately from its data source (to prevent processing updates)
        if (ViewMode.isConversationMode(mCurrentMode)) {
            mController.disablePagerUpdates();
        }

        mCurrentMode = newMode;
        LogUtils.i(LOG_TAG, "onViewModeChanged(%d)", newMode);

        // do all the real work in onMeasure/onLayout, when panes are sized and positioned for the
        // current width/height anyway
        requestLayout();
    }

    private void setPaneWidth(View pane, int w) {
        final ViewGroup.LayoutParams lp = pane.getLayoutParams();
        if (lp.width == w) {
            return;
        }
        lp.width = w;
        pane.setLayoutParams(lp);
    }

    @Override
    public void requestLayout() {
        Utils.checkRequestLayout(this);
        super.requestLayout();
    }

}
