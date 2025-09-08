package org.evochora.runtime.model;

import java.util.*;

/**
 * A wrapper around a Deque that tracks changes by calling a callback whenever the deque is modified.
 * This allows efficient change detection for lazy loading without modifying external code.
 *
 * @param <T> The type of elements in the deque.
 */
public class ChangeTrackingDeque<T> implements Deque<T> {
    private final Deque<T> delegate;
    private final Runnable onChangeCallback;
    
    /**
     * Creates a new ChangeTrackingDeque.
     *
     * @param delegate The underlying deque to wrap.
     * @param onChangeCallback The callback to invoke whenever the deque is modified.
     */
    public ChangeTrackingDeque(Deque<T> delegate, Runnable onChangeCallback) {
        this.delegate = delegate;
        this.onChangeCallback = onChangeCallback;
    }
    
    // Modification methods - call callback
    @Override
    public void addFirst(T e) {
        delegate.addFirst(e);
        onChangeCallback.run();
    }
    
    @Override
    public void addLast(T e) {
        delegate.addLast(e);
        onChangeCallback.run();
    }
    
    @Override
    public boolean offerFirst(T e) {
        boolean result = delegate.offerFirst(e);
        onChangeCallback.run();
        return result;
    }
    
    @Override
    public boolean offerLast(T e) {
        boolean result = delegate.offerLast(e);
        onChangeCallback.run();
        return result;
    }
    
    @Override
    public T removeFirst() {
        T result = delegate.removeFirst();
        onChangeCallback.run();
        return result;
    }
    
    @Override
    public T removeLast() {
        T result = delegate.removeLast();
        onChangeCallback.run();
        return result;
    }
    
    @Override
    public T pollFirst() {
        T result = delegate.pollFirst();
        if (result != null) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public T pollLast() {
        T result = delegate.pollLast();
        if (result != null) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public void push(T e) {
        delegate.push(e);
        onChangeCallback.run();
    }
    
    @Override
    public T pop() {
        T result = delegate.pop();
        onChangeCallback.run();
        return result;
    }
    
    @Override
    public boolean removeFirstOccurrence(Object o) {
        boolean result = delegate.removeFirstOccurrence(o);
        if (result) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public boolean removeLastOccurrence(Object o) {
        boolean result = delegate.removeLastOccurrence(o);
        if (result) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public boolean add(T e) {
        boolean result = delegate.add(e);
        onChangeCallback.run();
        return result;
    }
    
    @Override
    public boolean remove(Object o) {
        boolean result = delegate.remove(o);
        if (result) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public boolean addAll(Collection<? extends T> c) {
        boolean result = delegate.addAll(c);
        if (result) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public boolean removeAll(Collection<?> c) {
        boolean result = delegate.removeAll(c);
        if (result) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public boolean retainAll(Collection<?> c) {
        boolean result = delegate.retainAll(c);
        if (result) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public void clear() {
        delegate.clear();
        onChangeCallback.run();
    }
    
    // Read-only methods - no callback
    @Override
    public T getFirst() {
        return delegate.getFirst();
    }
    
    @Override
    public T getLast() {
        return delegate.getLast();
    }
    
    @Override
    public T peekFirst() {
        return delegate.peekFirst();
    }
    
    @Override
    public T peekLast() {
        return delegate.peekLast();
    }
    
    @Override
    public T peek() {
        return delegate.peek();
    }
    
    @Override
    public T element() {
        return delegate.element();
    }
    
    @Override
    public int size() {
        return delegate.size();
    }
    
    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }
    
    @Override
    public boolean contains(Object o) {
        return delegate.contains(o);
    }
    
    @Override
    public Iterator<T> iterator() {
        return delegate.iterator();
    }
    
    @Override
    public Iterator<T> descendingIterator() {
        return delegate.descendingIterator();
    }
    
    @Override
    public Object[] toArray() {
        return delegate.toArray();
    }
    
    @Override
    public <T1> T1[] toArray(T1[] a) {
        return delegate.toArray(a);
    }
    
    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.containsAll(c);
    }
    
    @Override
    public boolean offer(T e) {
        boolean result = delegate.offer(e);
        onChangeCallback.run();
        return result;
    }
    
    @Override
    public T poll() {
        T result = delegate.poll();
        if (result != null) {
            onChangeCallback.run();
        }
        return result;
    }
    
    @Override
    public T remove() {
        T result = delegate.remove();
        onChangeCallback.run();
        return result;
    }
}
