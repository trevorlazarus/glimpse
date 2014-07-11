package com.metsci.glimpse.support.texture;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;

import javax.imageio.ImageIO;
import javax.media.opengl.GL;
import javax.media.opengl.GL2;

import com.metsci.glimpse.support.texture.TextureProjected2D;
import com.metsci.glimpse.support.texture.ByteTextureProjected2D.MutatorByte2D;

/**
 * 
 * A texture class which stores 3 channel RGB colors. Each color channel
 * contains 8 bit values (capped from 0 to 255).
 * 
 * @author oren
 */
public class RGBTextureProjected2D extends TextureProjected2D {

    public static final int BYTES_PER_PIXEL = 3;
    
    public RGBTextureProjected2D(BufferedImage img) {
        this(img.getWidth(), img.getHeight(), false);
        setData(img);
    }
    
    public RGBTextureProjected2D(int dataSizeX, int dataSizeY) {
        this(dataSizeX, dataSizeY, false);
    }
    
    public RGBTextureProjected2D(int dataSizeX, int dataSizeY, boolean useVertexZCoord) {
        super(dataSizeX, dataSizeY, useVertexZCoord);
    }

    @Override
    protected void prepare_setData(GL2 gl) {

        for ( int i = 0; i < numTextures; i++ )
        {
            gl.glBindTexture( getTextureType( ), textureHandles[i] );

            prepare_setTexParameters( gl );
            Buffer positionedBuffer = prepare_setPixelStore( gl, i );
            gl.glTexImage2D( GL2.GL_TEXTURE_2D, 0, GL2.GL_RGB, texSizesX[i], texSizesY[i], 0, GL2.GL_RGB, GL2.GL_UNSIGNED_BYTE, positionedBuffer );
        }

        gl.glPixelStorei( GL2.GL_UNPACK_SKIP_PIXELS, 0 );
        gl.glPixelStorei( GL2.GL_UNPACK_SKIP_ROWS, 0 );
        gl.glPixelStorei( GL2.GL_UNPACK_ROW_LENGTH, 0 );
    }

    protected Buffer prepare_setPixelStore( GL gl, int i )
    {
        gl.glPixelStorei( GL2.GL_UNPACK_ALIGNMENT, 1 );
        gl.glPixelStorei( GL2.GL_UNPACK_SKIP_PIXELS, texStartsX[i] );
        gl.glPixelStorei( GL2.GL_UNPACK_ROW_LENGTH, dataSizeX );

        // for some reason, the following does not work:
        //gl.glPixelStorei( GL2.GL_UNPACK_SKIP_ROWS, texStartY[i] );
        // however, skipping rows manually using data.position works
        return data.position( texStartsY[i] * dataSizeX * BYTES_PER_PIXEL );
    }

    @Override
    protected int getRequiredCapacityBytes() {
        return BYTES_PER_PIXEL * dataSizeX * dataSizeY;
    }

    @Override
    protected float getData(int index) {
        int offset = index*BYTES_PER_PIXEL;
        byte r = data.get(offset);
        byte g = data.get(offset+1);
        byte b = data.get(offset+2);
        return (r << 16) | (g << 8) | b;
    }

    public void setData( InputStream in ) throws IOException
    {
        setData( ImageIO.read( in ) );
    }

    public void setData( final BufferedImage image )
    {
        setData0(image);
    }

    protected void setData0( final BufferedImage image)
    {
        resize( image.getWidth( ), image.getHeight( ) );
        
        lock.lock();
        try {
            data.rewind();
            final byte[] rgb = new byte[3];
            //Note: x and y here are in java image space, not texture space.
            for ( int y = dataSizeY-1; y >= 0; y-- ) {
                for ( int x = 0; x < dataSizeX; x++ ) {
                    int argb = image.getRGB(x, y);
                    rgb[0] = (byte) ((0x00ff0000 & argb) >> 16);
                    rgb[1] = (byte) ((0x0000ff00 & argb) >> 8);
                    rgb[2] = (byte) ((0x000000ff & argb));
                    data.put(rgb);
                }
            }
            makeDirty();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * For modifying the byte buffer directly, pixels should be packed as RGB.
     * @param mutator
     */
    public void mutate( MutatorByte2D mutator )
    {
        lock.lock( );
        try
        {
            data.rewind( );
            mutator.mutate( data, dataSizeX, dataSizeY );
            makeDirty( );
        }
        finally
        {
            lock.unlock( );
        }
    }
    
}