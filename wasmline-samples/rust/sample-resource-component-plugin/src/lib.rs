wit_bindgen::generate!({
    world: "resource-plugin",
});

use exports::wasmline::resource_fixture::resources::{
    Counter,
    CounterBorrow,
    Guest,
    GuestCounter,
};
use std::sync::atomic::{AtomicU32, Ordering};
use wasmline::resource_fixture::host::Callback;

struct ResourcePlugin;

struct CounterState {
    value: AtomicU32,
}

static DROP_COUNT: AtomicU32 = AtomicU32::new(0);

impl Guest for ResourcePlugin {
    type Counter = CounterState;

    fn inspect(value: CounterBorrow<'_>) -> u32 {
        value.get::<CounterState>().value.load(Ordering::Relaxed)
    }

    fn round_trip(value: Counter) -> Counter {
        value
    }

    fn callback_with_borrow(callback: &Callback, value: CounterBorrow<'_>) -> u32 {
        callback.call(Self::inspect(value))
    }

    fn consume_callback(callback: Callback, value: u32) -> u32 {
        callback.call(value)
    }

    fn trap_with_borrow(_value: CounterBorrow<'_>) {
        panic!("intentional resource borrow trap")
    }
}

impl GuestCounter for CounterState {
    fn new(initial: u32) -> Self {
        Self {
            value: AtomicU32::new(initial),
        }
    }

    fn get(&self) -> u32 {
        self.value.load(Ordering::Relaxed)
    }

    fn add(&self, delta: u32) -> u32 {
        self.value.fetch_add(delta, Ordering::Relaxed) + delta
    }

    fn drop_count() -> u32 {
        DROP_COUNT.load(Ordering::Relaxed)
    }
}

impl Drop for CounterState {
    fn drop(&mut self) {
        DROP_COUNT.fetch_add(1, Ordering::Relaxed);
    }
}

export!(ResourcePlugin);
