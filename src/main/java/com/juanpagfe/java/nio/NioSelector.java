package com.juanpagfe.java.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;

public class NioSelector
{
    private static final Logger logger = LoggerFactory.getLogger( NioSelector.class );
    private static final long THREAD_TERMINATION_TIMEOUT_MS = 5000;
    private static final long DEFAULT_TICK_MS = 20;
    private final Selector selector;
    private final Thread thread;
    private final long tickMs;
    private final Queue<Runnable> postOperationQueue;
    private volatile boolean open;

    public NioSelector() throws IOException
    {
        this( DEFAULT_TICK_MS );
    }

    public NioSelector( long tickMs ) throws IOException
    {
        if ( tickMs <= 0 )
            throw new IllegalArgumentException( "Tick period must be positive" );

        this.selector = Selector.open();
        this.postOperationQueue = new ConcurrentLinkedQueue<>();
        this.thread = new Thread( this::loop );
        this.thread.setName( "JavaNioExt selector event loop" );
        this.thread.setDaemon( false );
        this.thread.start();

        this.tickMs = tickMs;
        this.open = true;
    }

    synchronized void close()
    {
        if ( open )
        {
            if ( logger.isDebugEnabled() )
                logger.debug( "Selector is closing" );
            boolean interrupted = false;

            if ( thread.isAlive() )
            {
                thread.interrupt();

                try
                {
                    thread.join( THREAD_TERMINATION_TIMEOUT_MS );
                }
                catch ( InterruptedException e )
                {
                    interrupted = true;
                }

                if ( thread.isAlive() )
                    logger.error( "NetCrusher selector thread is still alive" );
            }

            int activeSelectionKeys = selector.keys().size();
            if ( activeSelectionKeys > 0 )
                logger.warn( String.format( "Selector still has %s selection keys. Have you closed all linked crushers before?",
                        activeSelectionKeys ) );

            try
            {
                selector.close();
            }
            catch ( IOException e )
            {
                logger.error( "Fail to close selector", e );
            }

            open = false;
            logger.debug( "Selector is closed" );

            if ( interrupted )
                Thread.currentThread().interrupt();
        }
    }

    public SelectionKey register( SelectableChannel channel,
                                  int options, SelectionKeyCallback callback )
    {
        return execute( () -> channel.register( selector, options, callback ) );
    }

    public <T> T execute( Callable<T> callable ) throws NioException
    {
        if ( open )
        {
            if ( Thread.currentThread().equals( thread ) )
            {
                try
                {
                    return callable.call();
                }
                catch ( Exception e )
                {
                    throw new NioException( "Fail to execute selector op", e );
                }
            }
            else
            {
                NioSelectorPostOp<T> postOperation = new NioSelectorPostOp<>( callable );
                postOperationQueue.add( postOperation );

                selector.wakeup();

                try
                {
                    return postOperation.await();
                }
                catch ( InterruptedException e )
                {
                    throw new NioException( "Reactor operation was interrupted", e );
                }
                catch ( ExecutionException e )
                {
                    throw new NioException( "Selector operation has failed", e );
                }
            }
        }
        else
        {
            throw new IllegalStateException( "Selector is closed" );
        }
    }

    private void loop()
    {
        logger.debug( "Selector event loop started" );

        while ( !Thread.currentThread().isInterrupted() )
        {
            int count;
            try
            {
                count = selector.select( tickMs );
            }
            catch ( ClosedSelectorException e )
            {
                break;
            }
            catch ( Exception e )
            {
                logger.error( "Error on select()", e );
                break;
            }

            if ( count > 0 )
            {
                Set<SelectionKey> keys = selector.selectedKeys();

                Iterator<SelectionKey> keyIterator = keys.iterator();
                while ( keyIterator.hasNext() )
                {
                    SelectionKey selectionKey = keyIterator.next();

                    if ( selectionKey.isValid() )
                    {
                        SelectionKeyCallback callback = ( SelectionKeyCallback ) selectionKey.attachment();
                        try
                        {
                            callback.execute( selectionKey );
                        }
                        catch ( Exception e )
                        {
                            logger.error( "Error while executing selection key callback", e );
                        }
                    }
                    else
                    {
                        logger.debug( "Selection key is invalid: {}", selectionKey );
                    }

                    keyIterator.remove();
                }
            }
            runPostOperations();
        }

        logger.debug( "Selector event loop has finished" );
    }

    private void runPostOperations()
    {
        while ( true )
        {
            Runnable postOperation = postOperationQueue.poll();
            if ( postOperation != null )
                postOperation.run();
            else
                break;
        }
    }

}
