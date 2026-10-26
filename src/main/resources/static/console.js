const watched = new Map();
let keepKey = false;

const statusDot = document.querySelector("#status-dot");
const statusText = document.querySelector("#status-text");
const accountBoard = document.querySelector("#accounts");
const resultStatus = document.querySelector("#result-status");
const resultBody = document.querySelector("#result-body");
const idempotencyKey = document.querySelector("#idempotency-key");
const entryList = document.querySelector("#entries");

document.querySelector("#refresh-health").addEventListener("click", checkHealth);
document.querySelector("#new-key").addEventListener("click", rotateKey);
document.querySelector("#keep-key").addEventListener("change", (event) => {
    keepKey = event.target.checked;
});
document.querySelector("#open-account").addEventListener("submit", openAccount);
document.querySelector("#record-transaction").addEventListener("submit", recordTransaction);
document.querySelector("#add-entry").addEventListener("click", () => addEntry());
document.querySelector("#reverse").addEventListener("submit", reverseTransaction);
document.querySelector("#payment").addEventListener("submit", sendPayment);
document.querySelector("#watch-form").addEventListener("submit", (event) => {
    event.preventDefault();
    watchAccount(document.querySelector("#watch-id").value.trim());
});

document.querySelectorAll("[data-preset]").forEach((button) => {
    button.addEventListener("click", () => runPreset(button.dataset.preset));
});

rotateKey();
addEntry("merchant-42", "DEBIT", "100.00");
addEntry("payouts-eur", "CREDIT", "98.50");
addEntry("fees-eur", "CREDIT", "1.50");
checkHealth();
["settlement-eur", "settlement-usd", "settlement-jpy"].forEach(watchAccount);

function rotateKey() {
    idempotencyKey.value = crypto.randomUUID();
}

function useKey() {
    const key = idempotencyKey.value.trim();
    if (!keepKey) {
        rotateKey();
    }
    return key;
}

async function checkHealth() {
    try {
        const response = await fetch("/actuator/health");
        const up = response.ok;
        statusDot.className = "dot " + (up ? "up" : "down");
        statusText.textContent = up ? "API pornit" : "API răspunde cu eroare";
    } catch (error) {
        statusDot.className = "dot down";
        statusText.textContent = "API oprit";
    }
}

async function call(method, path, { body, idempotent = false } = {}) {
    const headers = { Accept: "application/json" };
    if (body !== undefined) {
        headers["Content-Type"] = "application/json";
    }
    if (idempotent) {
        headers["Idempotency-Key"] = useKey();
    }
    const response = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    let payload = text;
    try {
        payload = text ? JSON.parse(text) : null;
    } catch (error) {
        payload = text;
    }
    showResult(response.status, payload, response.headers.get("Idempotent-Replayed"));
    return { ok: response.ok, status: response.status, payload };
}

function showResult(status, payload, replayed) {
    resultStatus.className = "badge " + (status >= 200 && status < 300 ? "ok" : "bad");
    resultStatus.textContent = String(status) + (replayed === "true" ? " replay" : "");
    if (typeof payload === "string" && payload.trim().startsWith("<")) {
        resultBody.textContent = "Răspunsul nu e JSON. Pornește aplicația și deschide consola la http://localhost:8080/.";
        return;
    }
    resultBody.textContent = typeof payload === "string" ? payload : JSON.stringify(payload, null, 2);
}

async function openAccount(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const body = {
        id: form.elements.id.value.trim(),
        currency: form.elements.currency.value,
        overdraftLimit: form.elements.overdraft.value.trim()
    };
    const result = await call("POST", "/v1/accounts", { body, idempotent: true });
    if (result.ok) {
        await watchAccount(body.id);
    }
}

async function recordTransaction(event) {
    event.preventDefault();
    const description = document.querySelector("#description").value.trim();
    const entries = [...entryList.querySelectorAll(".entry")].map((row) => ({
        accountId: row.querySelector("[data-field=account]").value.trim(),
        direction: row.querySelector("[data-field=direction]").value,
        amount: row.querySelector("[data-field=amount]").value.trim()
    }));
    const result = await call("POST", "/v1/transactions", {
        body: { description, entries },
        idempotent: true
    });
    if (result.ok && result.payload && result.payload.id) {
        document.querySelector("#reverse-id").value = result.payload.id;
        await refreshWatched();
    }
}

async function reverseTransaction(event) {
    event.preventDefault();
    const id = document.querySelector("#reverse-id").value.trim();
    const description = document.querySelector("#reverse-description").value.trim();
    const result = await call("POST", "/v1/transactions/" + encodeURIComponent(id) + "/reversals", {
        body: description ? { description } : {},
        idempotent: true
    });
    if (result.ok) {
        await refreshWatched();
    }
}

async function sendPayment(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const merchant = form.merchant.value.trim();
    const result = await call("POST", "/v1/samples/payment-settled", {
        body: {
            eventId: form.eventId.value.trim(),
            paymentId: form.paymentId.value.trim(),
            merchantAccountId: merchant,
            amount: form.amount.value.trim(),
            currency: form.currency.value
        }
    });
    if (result.status === 202) {
        await watchAccount(merchant);
        await watchAccount("settlement-" + form.currency.value.toLowerCase());
        await pollBalance(merchant, 8);
    }
}

async function pollBalance(accountId, attempts) {
    for (let i = 0; i < attempts; i += 1) {
        await delay(400);
        await watchAccount(accountId);
    }
}

function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

async function watchAccount(id) {
    if (!id) {
        return;
    }
    const result = await call("GET", "/v1/accounts/" + encodeURIComponent(id));
    if (result.ok) {
        watched.set(id, result.payload);
        renderAccounts();
    }
}

async function refreshWatched() {
    for (const id of [...watched.keys()]) {
        await watchAccount(id);
    }
}

function renderAccounts() {
    accountBoard.replaceChildren();
    if (watched.size === 0) {
        const empty = document.createElement("p");
        empty.className = "empty";
        empty.textContent = "Niciun cont urmărit încă.";
        accountBoard.append(empty);
        return;
    }
    for (const account of watched.values()) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "account " + (account.balance.startsWith("-") ? "negative" : "positive");
        const name = document.createElement("strong");
        name.textContent = account.id;
        const balance = document.createElement("span");
        balance.className = "balance";
        balance.textContent = account.balance;
        const meta = document.createElement("span");
        meta.className = "meta";
        meta.textContent = account.currency + (account.overdraftLimit == null ? " · fără limită" : " · limită " + account.overdraftLimit);
        button.append(name, balance, meta);
        button.addEventListener("click", () => loadStatement(account.id));
        accountBoard.append(button);
    }
}

async function loadStatement(accountId) {
    await call("GET", "/v1/accounts/" + encodeURIComponent(accountId) + "/entries?limit=8");
}

function addEntry(accountId = "", direction = "DEBIT", amount = "") {
    const row = document.createElement("div");
    row.className = "entry";
    row.innerHTML = `
        <input data-field="account" placeholder="cont" value="${escapeAttr(accountId)}" aria-label="Cont">
        <select data-field="direction" aria-label="Direcție">
            <option ${direction === "DEBIT" ? "selected" : ""}>DEBIT</option>
            <option ${direction === "CREDIT" ? "selected" : ""}>CREDIT</option>
        </select>
        <input data-field="amount" placeholder="0.00" value="${escapeAttr(amount)}" aria-label="Sumă">
        <button type="button" class="ghost" aria-label="Șterge linia">×</button>
    `;
    row.querySelector("button").addEventListener("click", () => {
        if (entryList.children.length > 2) {
            row.remove();
        }
    });
    entryList.append(row);
}

function escapeAttr(value) {
    return String(value).replaceAll('"', "&quot;");
}

async function runPreset(name) {
    if (name === "merchant") {
        await createNamed("merchant-42", "EUR", "0.00");
    } else if (name === "payouts") {
        await createNamed("payouts-eur", "EUR", "0.00");
    } else if (name === "fees") {
        await createNamed("fees-eur", "EUR", "0.00");
    } else if (name === "settle") {
        await createNamed("merchant-42", "EUR", "0.00");
        document.querySelector("#payment-event").value = "evt-" + Date.now();
        document.querySelector("#payment-id").value = "pay-" + Date.now();
        document.querySelector("#payment-merchant").value = "merchant-42";
        document.querySelector("#payment-amount").value = "125.50";
        document.querySelector("#payment-currency").value = "EUR";
        document.querySelector("#payment").requestSubmit();
    } else if (name === "payout") {
        document.querySelector("#description").value = "Payout merchant 42";
        entryList.replaceChildren();
        addEntry("merchant-42", "DEBIT", "100.00");
        addEntry("payouts-eur", "CREDIT", "98.50");
        addEntry("fees-eur", "CREDIT", "1.50");
        document.querySelector("#record-transaction").requestSubmit();
    }
}

async function createNamed(id, currency, overdraft) {
    const form = document.querySelector("#open-account");
    form.elements.id.value = id;
    form.elements.currency.value = currency;
    form.elements.overdraft.value = overdraft;
    const result = await call("POST", "/v1/accounts", {
        body: { id, currency, overdraftLimit: overdraft },
        idempotent: true
    });
    if (result.ok || result.status === 409) {
        await watchAccount(id);
    }
    return result;
}
