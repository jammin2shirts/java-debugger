import { TextLineStream } from "jsr:@std/streams/text-line-stream";

export type BridgeResponse = {
  ok: boolean;
  fields: Record<string, string>;
};

export type BridgeEvent = {
  name: string;
  fields: Record<string, string>;
};

type Pending = {
  resolve: (value: BridgeResponse) => void;
  reject: (reason?: unknown) => void;
};

const RESPONSE_TIMEOUT_MS = 30000;

export class BridgeClient {
  private readonly classpath: string;
  private process?: Deno.ChildProcess;
  private stdinWriter?: WritableStreamDefaultWriter<Uint8Array>;
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();
  private eventHandler: ((event: BridgeEvent) => void) | undefined;

  constructor(classpath: string) {
    this.classpath = classpath;
  }

  onEvent(handler: (event: BridgeEvent) => void): void {
    this.eventHandler = handler;
  }

  async start(): Promise<void> {
    const cmd = new Deno.Command("java", {
      args: ["-cp", this.classpath, "dev.javadebugger.bridge.BridgeMain"],
      stdin: "piped",
      stdout: "piped",
      stderr: "piped",
    });

    this.process = cmd.spawn();
    this.stdinWriter = this.process.stdin.getWriter();

    this.readOutput(this.process.stdout).catch((error) => {
      this.rejectAllPending(error);
    });

    this.readStderr(this.process.stderr).catch(() => {
      // Keep stderr best-effort only for diagnostics.
    });

    const ping = await this.request("ping", {});
    if (!ping.ok || ping.fields.message !== "pong") {
      throw new Error("Bridge did not respond to ping");
    }
  }

  async stop(): Promise<void> {
    try {
      await this.request("quit", {});
    } catch {
      // Process may already be gone.
    }

    if (this.stdinWriter) {
      await this.stdinWriter.close().catch(() => undefined);
      this.stdinWriter = undefined;
    }

    if (this.process) {
      this.process.kill("SIGTERM");
      await this.process.status.catch(() => undefined);
      this.process = undefined;
    }
  }

  async request(command: string, args: Record<string, string>): Promise<BridgeResponse> {
    const id = this.nextId++;
    const line = this.encodeLine(id, command, args) + "\n";

    const responsePromise = new Promise<BridgeResponse>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });

      const timeout = setTimeout(() => {
        if (this.pending.delete(id)) {
          reject(new Error(`Bridge request timed out: ${command}`));
        }
      }, RESPONSE_TIMEOUT_MS);

      const wrappedResolve = (value: BridgeResponse) => {
        clearTimeout(timeout);
        resolve(value);
      };

      const wrappedReject = (reason?: unknown) => {
        clearTimeout(timeout);
        reject(reason);
      };

      this.pending.set(id, { resolve: wrappedResolve, reject: wrappedReject });
    });

    const writer = this.stdinWriter;
    if (!writer) {
      throw new Error("Bridge stdin is not available");
    }

    await writer.write(new TextEncoder().encode(line));
    return await responsePromise;
  }

  private async readOutput(stdout: ReadableStream<Uint8Array>): Promise<void> {
    const decoder = stdout
      .pipeThrough(new TextDecoderStream())
      .pipeThrough(new TextLineStream());

    for await (const line of decoder) {
      const parsed = this.parseLine(line);
      if (!parsed) {
        continue;
      }

      if (parsed.kind === "event") {
        this.eventHandler?.({ name: parsed.eventName, fields: parsed.fields });
        continue;
      }

      const pending = this.pending.get(parsed.id);
      if (!pending) {
        continue;
      }
      this.pending.delete(parsed.id);

      if (parsed.status === "ok") {
        pending.resolve({ ok: true, fields: parsed.fields });
      } else {
        pending.resolve({ ok: false, fields: parsed.fields });
      }
    }
  }

  private async readStderr(stderr: ReadableStream<Uint8Array>): Promise<void> {
    const decoder = stderr
      .pipeThrough(new TextDecoderStream())
      .pipeThrough(new TextLineStream());

    for await (const line of decoder) {
      if (line.trim().length > 0) {
        console.error(`[bridge] ${line}`);
      }
    }
  }

  private rejectAllPending(error: unknown): void {
    for (const [, pending] of this.pending) {
      pending.reject(error);
    }
    this.pending.clear();
  }

  private encodeLine(id: number, command: string, args: Record<string, string>): string {
    const parts: string[] = [this.encode(String(id)), this.encode(command)];
    for (const [key, value] of Object.entries(args)) {
      parts.push(`${this.encode(key)}=${this.encode(value)}`);
    }
    return parts.join("|");
  }

  private parseLine(line: string):
    | { kind: "event"; eventName: string; fields: Record<string, string> }
    | { kind: "response"; id: number; status: "ok" | "error"; fields: Record<string, string> }
    | null {
    const parts = line.split("|");
    if (parts.length < 2) {
      return null;
    }

    const first = this.decode(parts[0]);
    const second = this.decode(parts[1]);
    const fields: Record<string, string> = {};

    for (let i = 2; i < parts.length; i += 1) {
      const token = parts[i];
      const split = token.indexOf("=");
      if (split <= 0) {
        continue;
      }
      const key = this.decode(token.slice(0, split));
      const value = this.decode(token.slice(split + 1));
      fields[key] = value;
    }

    if (first === "event") {
      return { kind: "event", eventName: second, fields };
    }

    const id = Number(first);
    if (!Number.isFinite(id)) {
      return null;
    }

    const status = second === "ok" ? "ok" : "error";
    return { kind: "response", id, status, fields };
  }

  private encode(value: string): string {
    return encodeURIComponent(value ?? "");
  }

  private decode(value: string): string {
    try {
      return decodeURIComponent(value ?? "");
    } catch {
      return value;
    }
  }
}
