package com.metsci.glimpse.plot.timeline.animate;

import static com.metsci.glimpse.plot.timeline.animate.DragUtils.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.metsci.glimpse.context.GlimpseBounds;
import com.metsci.glimpse.context.GlimpseTargetStack;
import com.metsci.glimpse.context.TargetStackUtil;
import com.metsci.glimpse.event.mouse.GlimpseMouseAllAdapter;
import com.metsci.glimpse.event.mouse.GlimpseMouseEvent;
import com.metsci.glimpse.event.mouse.MouseButton;
import com.metsci.glimpse.layout.GlimpseLayout;
import com.metsci.glimpse.painter.decoration.BackgroundPainter;
import com.metsci.glimpse.plot.stacked.PlotInfo;
import com.metsci.glimpse.plot.stacked.StackedPlot2D.Orientation;
import com.metsci.glimpse.plot.timeline.CollapsibleTimePlot2D;
import com.metsci.glimpse.plot.timeline.StackedTimePlot2D;
import com.metsci.glimpse.plot.timeline.group.GroupInfo;
import com.metsci.glimpse.plot.timeline.layout.TimePlotInfo;
import com.metsci.glimpse.plot.timeline.listener.PlotMouseAdapter;
import com.metsci.glimpse.plot.timeline.listener.PlotMouseListener.PlotLocation;
import com.metsci.glimpse.support.repaint.RepaintManager;

/**
 * <p>Attaches to an existing {@link StackedTimePlot2D} to allow animated rearrangement
 * of the {@link PlotInfo} by the user. The user can click and drag {@link GroupInfo}
 * headers or {@link TimePlotInfo} labels to rearrange them.</p>
 * 
 * @author ulman
 */
public class DragManager
{
    public static final long TICK_TIME_MILLIS = 20;
    public static final double GROW_PIXELS_PER_TICK = 12;

    // constructor arguments
    protected StackedTimePlot2D plot;
    protected RepaintManager manager;
    protected Orientation orient;

    // Invariant: the total size of shrinking + growing PlotInfo should
    // always equal this size
    // when the animation is complete, the shrinking Collection will be
    // empty and the growing PlotInfo will be size
    protected int size;

    // the current x/y (depending on orientation) of the plots being dragged
    protected int dragPosition;
    protected int prevDragPosition;
    // the user clicks somewhere in the center of the plot, so the actual
    // top of the plot will dragPosition+dragOffset
    protected int dragOffset;

    // sorted list of the PlotInfos currently being dragged by the user
    protected List<DragInfo> dragging;
    protected volatile boolean dragHappening = false;

    // fake/blank PlotInfos which are acting as spacing (to control the animation)
    // and are shrinking to make room for the new growing PlotInfo
    protected Map<DragKey, DragInfo> shrinking;

    // fake/blank PlotInfo representing where the PlotInfos being dragged will be
    // placed when the user lets up the mouse
    protected DragInfo growing;

    // background layout used to mask things behind dragged plots
    protected GlimpseLayout backgroundLayout;

    // update thread used to control animation
    protected ScheduledExecutorService executor;

    protected boolean allowNestedGroups = false;

    public static DragManager attach( StackedTimePlot2D plot, RepaintManager manager )
    {
        return new DragManager( plot, manager );
    }

    public DragManager( final StackedTimePlot2D plot, final RepaintManager manager )
    {
        this.manager = manager;
        this.plot = plot;
        this.orient = plot.getOrientation( );

        this.backgroundLayout = new GlimpseLayout( );
        this.backgroundLayout.addPainter( new BackgroundPainter( ).setColor( plot.getBackgroundColor( ) ) );

        // create a listener which is notified when MouseEvents happen inside
        // any of the StackedPlot2D's PlotInfo
        this.plot.addPlotMouseListener( new PlotMouseAdapter( )
        {
            @Override
            public void mouseReleased( final GlimpseMouseEvent event, PlotInfo info, PlotLocation location )
            {
                manager.asyncExec( new Runnable( )
                {
                    @Override
                    public void run( )
                    {
                        if ( dragHappening )
                        {
                            endDrag( event );
                        }
                    }
                } );
            }

            @Override
            public void mouseMoved( final GlimpseMouseEvent event, final PlotInfo info, final PlotLocation location )
            {
                manager.asyncExec( new Runnable( )
                {
                    @Override
                    public void run( )
                    {
                        boolean isButton1 = event.isButtonDown( MouseButton.Button1 );
                        boolean isGroup = info instanceof GroupInfo;
                        boolean isLabel = location == PlotLocation.Label;

                        if ( !dragHappening && isButton1 && ( isGroup || isLabel ) )
                        {
                            startDrag( event, info, location );
                        }
                    }
                } );
            }
        } );

        // create a MouseListener which is notified of any mouse events within the StackedPlot2D
        this.plot.getFullOverlayLayout( ).addGlimpseMouseAllListener( new GlimpseMouseAllAdapter( )
        {
            @Override
            public void mouseReleased( final GlimpseMouseEvent event )
            {
                manager.asyncExec( new Runnable( )
                {
                    @Override
                    public void run( )
                    {
                        if ( dragHappening )
                        {
                            endDrag( event );
                        }
                    }
                } );
            }

            @Override
            public void mouseMoved( final GlimpseMouseEvent event )
            {
                if ( event.isButtonDown( MouseButton.Button1 ) )
                {
                    // update dragPosition
                    if ( dragHappening )
                    {
                        manager.asyncExec( new Runnable( )
                        {
                            @Override
                            public void run( )
                            {
                                // check dragHappening again, it may have changed since
                                // we checked outside of asyncExec
                                if ( dragHappening )
                                {

                                    // record the position of the click relative to the StackedPlot2D
                                    prevDragPosition = dragPosition;
                                    dragPosition = getDragPosition( event );

                                    // check if the growing PlotInfo should be changed
                                    chooseNewGrowing( event );

                                    // update layout data for dragged plots and animate
                                    updateLayoutData( );
                                    plot.validate( );
                                }
                            }
                        } );

                        // call setHandled outside of asyncExec
                        // (otherwise it would have no effect)
                        event.setHandled( true );
                    }
                }
            }
        } );
    }

    public void isAllowNestedGroups( boolean allowNested )
    {
        this.allowNestedGroups = allowNested;
    }

    public boolean isAllowNestedGroups( )
    {
        return this.allowNestedGroups;
    }

    protected void stopUpdateThread( )
    {
        if ( this.executor != null )
        {
            this.executor.shutdown( );
            this.executor = null;
        }
    }

    protected void startUpdateThread( )
    {
        if ( this.executor != null )
        {
            this.executor.shutdown( );
        }

        // create a thread that updates the gaps between PlotInfos (in order
        // to animate them sliding around in response to the user)
        this.executor = Executors.newScheduledThreadPool( 1 );
        this.executor.scheduleWithFixedDelay( new Runnable( )
        {
            @Override
            public void run( )
            {
                manager.syncExec( new Runnable( )
                {
                    @Override
                    public void run( )
                    {
                        if ( dragHappening )
                        {
                            updateGrowingShrinking( );
                            plot.validate( );
                        }
                    }
                } );
            }
        }, TICK_TIME_MILLIS, TICK_TIME_MILLIS, TimeUnit.MILLISECONDS );
    }

    //XXX I don't think this will work under all circumstances
    //XXX particularly with groups -- there's no way to say
    //XXX "I don't want this in a group, I want it on the same level
    //XXX as the group -- or on the top level"
    //XXX
    //XXX perhaps the user must hold down a modifier key, or drag
    //XXX over the group name
    protected void applyDrag( GlimpseMouseEvent event )
    {
        List<PlotInfo> list = normalizeOrder( );

        DragInfo topDrag = dragging.get( 0 );

        // if the PlotInfo being dragged was in a group, remove it from the group
        GroupInfo oldParent = findParent( list, topDrag.info );
        if ( oldParent != null )
        {
            oldParent.removeChildPlot( topDrag.info );
        }

        // find the PlotInfo which should sit directly above topDrag
        // once the drag is complete
        PlotInfo newPosition = null;
        if ( growing.top )
        {
            int index = getIndex( list, growing.info );
            if ( index > 0 )
            {
                newPosition = list.get( index - 1 );
            }
        }
        else
        {
            newPosition = growing.info;
        }

        // if we're dragging a group, and nested groups are not allowed
        // then the group we're dragging should be placed on the top level
        // (i.e. it should have no parent, so newParent == null)
        GroupInfo newParent = null;
        if ( topDrag.info instanceof GroupInfo && !allowNestedGroups )
        {
            newParent = null;
        }
        else if ( newPosition instanceof GroupInfo )
        {
            newParent = ( GroupInfo ) newPosition;
        }
        else
        {
            newParent = findParent( list, newPosition );
        }

        // if the plot was dragged into a new group, add it to the group
        if ( newParent != null ) newParent.addChildPlot( topDrag.info );

        // adjust the dragged plot's ordering constant
        if ( growing.top )
        {
            topDrag.info.setOrder( growing.info.getOrder( ) - 1 );
        }
        else
        {
            topDrag.info.setOrder( growing.info.getOrder( ) + 1 );
        }
    }

    protected void endDrag( GlimpseMouseEvent event )
    {
        applyDrag( event );

        for ( DragInfo drag : dragging )
        {
            // set custom layout data to null
            // this causes StackedPlot to resume handling positioning of PlotInfo
            drag.info.setLayoutData( null );

            // set z order back to 0
            plot.setZOrder( drag.info.getBaseLayout( ), 0 );
        }

        // reset column/row constraints (depending on orientation)
        // which were being used to create animated gaps between components
        if ( plot.getOrientation( ) == Orientation.VERTICAL )
        {
            plot.getLayoutManager( ).setRowConstraints( null );
        }
        else
        {
            plot.getLayoutManager( ).setColumnConstraints( null );
        }

        plot.removeLayout( this.backgroundLayout );

        // reset flags
        dragHappening = false;
        dragging = null;

        // stop the update thread (which animates the gap spacing changes)
        stopUpdateThread( );

        plot.validate( );
    }

    // resets the order constants of the PlotInfo as follows:
    //
    // top plot = 0
    // next = 2
    // ...
    // timeline = n * 2
    //
    // this allows the DragManager to reliably insert PlotInfos above
    // or below any existing PlotInfo by using odd numbered order constants
    protected List<PlotInfo> normalizeOrder( )
    {
        boolean autoValidate = plot.isAutoValidate( );
        plot.setAutoValidate( false );

        List<PlotInfo> list = plot.getSortedPlots( );
        for ( int i = 0; i < list.size( ); i++ )
        {
            PlotInfo info = list.get( i );
            info.setOrder( i * 2 );
        }

        plot.validate( );
        plot.setAutoValidate( autoValidate );

        return list;
    }

    protected int getDragPosition( GlimpseMouseEvent event )
    {
        // event with coordinates relative to the StackedPlot2D
        GlimpseMouseEvent stackedPlotCoord = TargetStackUtil.translateCoordinates( event, plot );
        GlimpseBounds plotBounds = stackedPlotCoord.getTargetStack( ).getBounds( );

        // record the position of the click relative to the StackedPlot2D
        if ( orient == Orientation.VERTICAL )
        {
            return getSize( orient, plotBounds ) - getCoordinate( orient, stackedPlotCoord );
        }
        else
        {
            return getCoordinate( orient, stackedPlotCoord );
        }
    }

    protected int getDragOffset( GlimpseMouseEvent event, PlotInfo info )
    {
        // event with coordinates relative to PlotInfo
        GlimpseMouseEvent baseLayoutCoord = TargetStackUtil.translateCoordinates( event, info.getBaseLayout( ) );
        GlimpseBounds baseLayoutBounds = baseLayoutCoord.getTargetStack( ).getBounds( );

        // record the offset of the click from the bottom of the clicked PlotInfo
        if ( orient == Orientation.VERTICAL )
        {
            return getCoordinate( orient, baseLayoutCoord );
        }
        else
        {
            return getSize( orient, baseLayoutBounds ) - getCoordinate( orient, baseLayoutCoord );
        }
    }

    protected void startDrag( GlimpseMouseEvent event, PlotInfo info, PlotLocation location )
    {
        dragHappening = true;

        // save all the plots being dragged
        if ( info instanceof GroupInfo )
        {
            GroupInfo group = ( GroupInfo ) info;
            dragging = getDragInfoList( orient, event, getSortedDescendants( group ) );
        }
        else
        {
            dragging = getDragInfoList( orient, event, Collections.singletonList( info ) );
        }

        // calculate the total size of the plots being dragged so we know how much space to create
        size = getSpacerSize( orient, plot.getSortedPlots( ), dragging );

        dragPosition = getDragPosition( event );
        prevDragPosition = dragPosition;

        // record the offset of the click from the bottom of the clicked PlotInfo
        dragOffset = getDragOffset( event, info );

        // set dragged plots z order so they draw in front of all other plots
        for ( DragInfo drag : dragging )
        {
            plot.setZOrder( drag.info.getBaseLayout( ), Integer.MAX_VALUE );
        }

        // add background layout which sits under dragged plots and prevents plots under them from showing through
        plot.addLayout( this.backgroundLayout, Integer.MAX_VALUE - 1 );

        // create an initial gap in the non-dragged PlotInfos equal to the total size of the dragged PlotInfos
        // the end result should be that nothing changes position the moment the drag starts
        initializeGrowingShrinking( dragging.get( 0 ) );

        startUpdateThread( );
        updateLayoutData( );
        updateGrowingShrinking( );

        plot.validate( );
    }

    protected void updateLayoutData( )
    {
        int pos = dragPosition + dragOffset;

        // don't extend the backdrop behind the dragged PlotInfo by
        // the size of the plot spacing of first plot
        // (there is no dragged plot above it)
        int backgroundSize = size - dragging.get( 0 ).info.getPlotSpacing( );

        // adjust the background layout to sit behind all PlotInfo being dragged (so PlotInfo behind
        // them which are not being dragged don't show through)
        if ( plot.getOrientation( ) == Orientation.VERTICAL )
        {
            this.backgroundLayout.setLayoutData( String.format( "pos container.x+%3$d %d container.x2-%3$d %d", pos - backgroundSize, pos, plot.getBorderSize( ) ) );
        }
        else
        {
            this.backgroundLayout.setLayoutData( String.format( "pos %d container.x+%3$d %d container.x2-%3$d", pos - backgroundSize, pos, plot.getBorderSize( ) ) );
        }

        // set the position of each PlotInfo being dragged (absolute position
        // instead of cell-based like usual)
        for ( DragInfo drag : dragging )
        {
            int bottom = ( int ) ( pos - drag.size );
            int top = pos;
            int border = plot.getBorderSize( );

            if ( plot.getOrientation( ) == Orientation.VERTICAL )
            {
                drag.info.setLayoutData( String.format( "pos container.x+%3$d %d container.x2-%3$d %d", bottom, top, border ) );
            }
            else
            {
                drag.info.setLayoutData( String.format( "pos %d container.y+%3$d %d container.y2-%3$d", bottom, top, border ) );
            }

            pos -= drag.size + drag.info.getPlotSpacing( );
        }
    }

    protected void initializeGrowingShrinking( DragInfo dragInfo )
    {
        growing = new DragInfo( dragInfo, size );
        shrinking = new HashMap<DragKey, DragInfo>( );
    }

    protected void chooseNewGrowing( GlimpseMouseEvent event )
    {
        if ( shouldChooseUnnested( ) )
        {
            chooseNewGrowingUnnested( event );
        }
        else
        {
            chooseNewGrowingNested( event );
        }
    }

    protected void chooseNewGrowingNested( GlimpseMouseEvent event )
    {
        GlimpseTargetStack stack = TargetStackUtil.popTo( event.getTargetStack( ), plot );

        // check each PlotInfo, find the one we're currently dragging over
        for ( PlotInfo info : plot.getAllPlots( ) )
        {
            if ( chooseNewGrowing( stack, info, info ) ) return;
        }
    }

    protected boolean shouldChooseUnnested( )
    {
        DragInfo topDrag = dragging.get( 0 );
        return !allowNestedGroups && topDrag.info instanceof GroupInfo && plot instanceof CollapsibleTimePlot2D;
    }

    protected void chooseNewGrowingUnnested( GlimpseMouseEvent event )
    {
        GlimpseTargetStack stack = TargetStackUtil.popTo( event.getTargetStack( ), plot );

        // only iterate through ungrouped plots and top level groups
        CollapsibleTimePlot2D groupPlot = ( CollapsibleTimePlot2D ) plot;
        for ( PlotInfo topLevelInfo : groupPlot.getUngroupedPlots( ) )
        {
            if ( topLevelInfo instanceof GroupInfo )
            {
                GroupInfo groupInfo = ( GroupInfo ) topLevelInfo;
                List<PlotInfo> groupList = getSortedDescendants( groupInfo );

                PlotInfo bottomPlot = groupList.get( groupList.size( ) - 1 );
                PlotInfo topPlot = groupList.get( 0 );

                if ( chooseNewGrowing( stack, bottomPlot, topPlot ) ) return;
            }
        }
    }

    protected boolean chooseNewGrowing( GlimpseTargetStack stack, PlotInfo bottomPlot, PlotInfo topPlot )
    {
        // <0 is dragging toward top of screen, >0 toward bottom
        boolean top = prevDragPosition - dragPosition < 0;
        int checkPosition = top ? dragPosition + dragOffset : dragPosition + dragOffset - size;

        GlimpseBounds bottomBounds = bottomPlot.getBaseLayout( ).getTargetBounds( stack );
        GlimpseBounds topBounds = topPlot.getBaseLayout( ).getTargetBounds( stack );

        int bottomPos = getBottom( orient, bottomBounds );
        int topPos = getTop( orient, topBounds );
        int middlePos = ( topPos - bottomPos ) / 2 + bottomPos;

        PlotInfo info = top ? topPlot : bottomPlot;

        boolean isInsideTop = top && checkPosition > middlePos && checkPosition < topPos;
        boolean isInsideBottom = !top && checkPosition > bottomPos && checkPosition < middlePos;
        boolean isInside = isInsideTop || isInsideBottom;
        boolean isNotGrowing = growing != null && ( growing.size == size || !info.equals( growing.info ) );
        boolean isNotTimeline = !info.equals( plot.getTimelinePlotInfo( ) );

        if ( isInside && isNotGrowing && isNotTimeline )
        {
            int growSize = 0;

            if ( plot.getOrientation( ) == Orientation.HORIZONTAL ) top = !top;

            // if the PlotInfo we are about to make grow is currently
            // shrinking, take over its space
            DragInfo shrinkInfo = shrinking.remove( new DragKey( info, top ) );
            if ( shrinkInfo != null )
            {
                growSize += shrinkInfo.size;
            }

            // the current growing PlotInfo should now start shrinking
            shrinking.put( new DragKey( growing ), growing );
            growing = new DragInfo( info, growSize, top );

            return true;
        }
        else
        {
            return false;
        }
    }

    protected void updateGrowingShrinking( )
    {
        // calculate amount to grow/shrink the gaps
        int amountToGrow = ( int ) Math.min( GROW_PIXELS_PER_TICK, size - growing.size );

        double totalShrinkingSize = size - growing.size;

        // update growing
        growing.size += amountToGrow;

        // update shrinking
        if ( !shrinking.isEmpty( ) )
        {
            Iterator<DragInfo> iter = shrinking.values( ).iterator( );
            while ( iter.hasNext( ) )
            {
                // the shrinking plots must shrink by the amount the grow plot grew
                // the shrink amount is spread proportionally based on size
                // (larger gaps shrink faster)
                DragInfo drag = iter.next( );
                double shrinkingSize = drag.size;
                double amountToShrink = amountToGrow * ( shrinkingSize / totalShrinkingSize );

                // always shrink by at least 0.5 pixels
                amountToShrink = Math.max( 0.5, amountToShrink );

                // never shrink below 0
                shrinkingSize = Math.max( 0, shrinkingSize - amountToShrink );

                if ( shrinkingSize == 0 )
                {
                    iter.remove( );
                }
                else
                {
                    drag.size = shrinkingSize;
                }
            }
        }

        List<PlotInfo> list = plot.getSortedPlots( );
        int[] offsets = new int[list.size( ) + 1];

        // add the top border
        offsets[0] += plot.getBorderSize( );

        for ( int i = 0; i < list.size( ); i++ )
        {
            PlotInfo info = list.get( i );

            DragInfo shrinkTop = shrinking.get( new DragKey( info, true ) );
            DragInfo shrinkBottom = shrinking.get( new DragKey( info, false ) );

            if ( info.equals( growing.info ) )
            {
                int size = ( int ) growing.size;
                int index = growing.top ? i : i + 1;
                offsets[index] += size;
            }

            if ( shrinkTop != null )
            {
                int size = ( int ) Math.round( shrinkTop.size );
                offsets[i] += size;
            }

            if ( shrinkBottom != null )
            {
                int size = ( int ) Math.round( shrinkBottom.size );
                offsets[i + 1] += size;
            }
        }

        // add the bottom border
        offsets[list.size( )] += plot.getBorderSize( );

        // build the column constraints string
        StringBuilder s = new StringBuilder( );
        for ( int i = 0; i < offsets.length; i++ )
        {
            s.append( String.format( "%s", offsets[i] ) );
            if ( i < offsets.length - 1 ) s.append( "[]" );
        }

        String constraints = s.toString( );

        // set column/row constraints (depending on orientation) on the StackedPlotInfo
        // to create animated (size changing) gaps in between the non-dragged PlotInfo
        if ( plot.getOrientation( ) == Orientation.VERTICAL )
        {
            plot.getLayoutManager( ).setRowConstraints( constraints );
        }
        else if ( plot.getOrientation( ) == Orientation.HORIZONTAL )
        {
            plot.getLayoutManager( ).setColumnConstraints( constraints );
        }
    }
}