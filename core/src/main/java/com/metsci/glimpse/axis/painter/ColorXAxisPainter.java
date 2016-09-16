/*
 * Copyright (c) 2016, Metron, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Metron, Inc. nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL METRON, INC. BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.metsci.glimpse.axis.painter;

import javax.media.opengl.GL;
import javax.media.opengl.GL3;

import com.metsci.glimpse.axis.Axis1D;
import com.metsci.glimpse.axis.painter.label.AxisLabelHandler;
import com.metsci.glimpse.context.GlimpseBounds;
import com.metsci.glimpse.context.GlimpseContext;
import com.metsci.glimpse.gl.texture.ColorTexture1D;
import com.metsci.glimpse.gl.util.GLUtils;
import com.metsci.glimpse.support.color.GlimpseColor;
import com.metsci.glimpse.support.line.LinePath;
import com.metsci.glimpse.support.line.LineProgram;
import com.metsci.glimpse.support.line.LineStyle;
import com.metsci.glimpse.support.shader.ColorTexture1DProgram;
import com.metsci.glimpse.support.shader.GLStreamingBufferBuilder;

/**
 * A horizontal (x) axis with a color bar and labeled ticks along the bottom.
 *
 * @author ulman
 * @see com.metsci.glimpse.examples.axis.MultiAxisPlotExample
 */
public class ColorXAxisPainter extends NumericXAxisPainter
{
    protected ColorTexture1DProgram progTex;
    protected GLStreamingBufferBuilder xyBuffer;
    protected GLStreamingBufferBuilder sBuffer;
    protected ColorTexture1D colorTexture;

    protected LineProgram progOutline;
    protected LinePath pathOutline;
    protected LineStyle style;

    protected int colorBarSize = 10;
    protected boolean outline = true;

    public ColorXAxisPainter( AxisLabelHandler ticks )
    {
        super( ticks );

        this.pathOutline = new LinePath( );
        this.xyBuffer = new GLStreamingBufferBuilder( );
        this.sBuffer = new GLStreamingBufferBuilder( );

        this.style = new LineStyle( );
        this.style.stippleEnable = false;
        this.style.thickness_PX = 1.0f;
        this.style.feather_PX = 0.0f;
        this.style.rgba = GlimpseColor.getBlack( );

        setTickSize( this.colorBarSize + 0 );
    }

    public void setEnableOutline( boolean doOutline )
    {
        this.outline = doOutline;
    }

    public void setColorScale( ColorTexture1D colorTexture )
    {
        this.colorTexture = colorTexture;
    }

    public void setColorBarSize( int size )
    {
        this.colorBarSize = size;
        setTickSize( colorBarSize + 2 );
    }

    @Override
    public void doPaintTo( GlimpseContext context )
    {
        GL3 gl = context.getGL( ).getGL3( );
        Axis1D axis = getAxis1D( context );
        GlimpseBounds bounds = getBounds( context );

        updateTextRenderer( );
        if ( textRenderer == null ) return;

        paintColorScale( context );
        paintTicks( gl, axis, bounds );
        paintAxisLabel( gl, axis, bounds );
        paintSelectionLine( gl, axis, bounds );
    }

    @Override
    protected void initShaders( )
    {
        super.initShaders( );

        progOutline = new LineProgram( );
        progTex = new ColorTexture1DProgram( );
    }

    protected void paintColorScale( GlimpseContext context )
    {
        if ( colorTexture != null )
        {
            GlimpseBounds bounds = getBounds( context );
            GL3 gl = context.getGL( ).getGL3( );

            int height = bounds.getHeight( );
            int width = bounds.getWidth( );

            float y1 = getColorBarMinY( height );
            float y2 = getColorBarMaxY( height );

            pathOutline.clear( );
            pathOutline.lineTo( 0.5f, y2 );
            pathOutline.lineTo( 0.5f, y1 );
            pathOutline.lineTo( width, y1 );
            pathOutline.lineTo( width, y2 );
            pathOutline.lineTo( 0.5f, y2 );

            xyBuffer.clear( );
            xyBuffer.addQuad2f( 0.5f, y1, width, y2 );

            sBuffer.clear( );
            sBuffer.addQuad1f( 0, 0, 1, 1 );

            GLUtils.enableStandardBlending( gl );
            try
            {
                // draw color scale
                progTex.begin( gl );
                try
                {
                    progTex.setPixelOrtho( gl, bounds );

                    progTex.draw( gl, colorTexture, xyBuffer, sBuffer );
                }
                finally
                {
                    progTex.end( gl );
                }

                // draw outline box
                progOutline.begin( gl );
                try
                {
                    progOutline.setPixelOrtho( gl, bounds );
                    progOutline.setViewport( gl, bounds );

                    progOutline.draw( gl, style, pathOutline );
                }
                finally
                {
                    progOutline.end( gl );
                }
            }
            finally
            {
                gl.glDisable( GL.GL_BLEND );
            }
        }
    }

    protected float getColorBarMinY( int height )
    {
        return height - tickBufferSize - colorBarSize - 0.5f;
    }

    protected float getColorBarMaxY( int height )
    {
        return height - tickBufferSize - 0.5f;
    }
}
