package core.amazake.systems.corepython.host;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** Обёртки задач. */
final class Tasks {

    /** Задача, которой уже нет (не запустилась). */
    static final Task DONE = new Task() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    private Tasks() {
    }

    static Task of(final ScheduledTask t) {
        if (t == null) {
            return DONE;
        }
        return new Task() {
            @Override
            public void cancel() {
                t.cancel();
            }

            @Override
            public boolean isCancelled() {
                return t.isCancelled() || t.getExecutionState() == ScheduledTask.ExecutionState.FINISHED;
            }
        };
    }

    /** Задача скрипта: помнит, что её отменили, и снимает себя из списка скрипта. */
    static final class Owned implements Task {
        private final PyScript script;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Task inner = DONE;

        Owned(final PyScript script) {
            this.script = script;
        }

        void bind(final Task t) {
            this.inner = t;
            if (this.cancelled.get()) {
                t.cancel();
            }
        }

        @Override
        public void cancel() {
            if (this.cancelled.compareAndSet(false, true)) {
                this.inner.cancel();
                this.script.forget(this);
            }
        }

        /** Один запуск закончился — для одноразовых задач. */
        void finished() {
            if (this.cancelled.compareAndSet(false, true)) {
                this.script.forget(this);
            }
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled.get() || !this.script.isLive();
        }

        @Override
        public String toString() {
            return "Task[" + (this.isCancelled() ? "отменена" : "активна") + "]";
        }
    }
}
