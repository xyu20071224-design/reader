#!/usr/bin/env node

const expression = process.argv.slice(2).join(" ") || "document.documentElement.outerHTML";
const targets = await (await fetch("http://127.0.0.1:9222/json")).json();
if (!targets[0]) throw new Error("No debuggable WebView found");

const socket = new WebSocket(targets[0].webSocketDebuggerUrl);
const result = await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("DevTools response timed out")), 5000);
  socket.addEventListener("open", () => {
    socket.send(
      JSON.stringify({
        id: 1,
        method: "Runtime.evaluate",
        params: { expression, returnByValue: true },
      }),
    );
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id !== 1) return;
    clearTimeout(timer);
    resolve(message);
  });
  socket.addEventListener("error", reject);
});
socket.close();
console.log(JSON.stringify(result, null, 2));
