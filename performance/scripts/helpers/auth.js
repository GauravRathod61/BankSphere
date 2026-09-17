import http from 'k6/http';
import { check } from 'k6';

/**
 * Register a new customer via API Gateway
 */
export function registerCustomer(baseUrl, user) {
    const payload = JSON.stringify({
        firstName: user.firstName || 'Perf',
        lastName: user.lastName || 'Tester',
        email: user.email,
        phoneNumber: user.phoneNumber,
        address: user.address || '123 Performance St',
        password: user.password || 'PerfTest@123'
    });

    const res = http.post(`${baseUrl}/customers`, payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, {
        'customer registered successfully': (r) => r.status === 201,
    });

    if (res.status === 201) {
        return JSON.parse(res.body);
    }
    return null;
}

/**
 * Log in a customer and obtain JWT token
 */
export function loginCustomer(baseUrl, email, password) {
    const payload = JSON.stringify({
        email: email,
        password: password || 'PerfTest@123'
    });

    const res = http.post(`${baseUrl}/auth/login`, payload, {
        headers: { 'Content-Type': 'application/json' },
    });

    check(res, {
        'login successful': (r) => r.status === 200,
    });

    if (res.status === 200) {
        const body = JSON.parse(res.body);
        return {
            token: body.token,
            customerId: body.customerId,
            authHeader: {
                'Authorization': `Bearer ${body.token}`,
                'Content-Type': 'application/json'
            }
        };
    }
    return null;
}

/**
 * Create a new bank account for an authenticated customer
 */
export function createAccount(baseUrl, customerId, accountType, authHeader) {
    const payload = JSON.stringify({
        customerId: customerId,
        accountType: accountType || 'CURRENT'
    });

    const res = http.post(`${baseUrl}/accounts`, payload, {
        headers: authHeader,
    });

    check(res, {
        'account created successfully': (r) => r.status === 201,
    });

    if (res.status === 201) {
        return JSON.parse(res.body);
    }
    console.error(`createAccount failed: status=${res.status}, body=${res.body}`);
    return null;
}

/**
 * Create customer, authenticate, create checking account, and optionally deposit initial funds
 */
export function provisionCustomerWithAccount(baseUrl, index, initialBalance = 10000) {
    const timestamp = Date.now();
    const uniqueSuffix = `${index}_${timestamp}_${Math.floor(Math.random() * 10000)}`;
    const email = `perf_${uniqueSuffix}@example.com`;
    const phoneNumber = `9${String(timestamp).slice(-5)}${String(index).padStart(4, '0')}`;
    const password = 'PerfPassword@123';

    const customer = registerCustomer(baseUrl, {
        firstName: `User${index}`,
        lastName: 'Bench',
        email: email,
        phoneNumber: phoneNumber,
        password: password
    });

    if (!customer) {
        throw new Error(`Failed to register customer ${email}`);
    }

    const auth = loginCustomer(baseUrl, email, password);
    if (!auth) {
        throw new Error(`Failed to login customer ${email}`);
    }

    const account = createAccount(baseUrl, customer.id, 'CURRENT', auth.authHeader);
    if (!account) {
        throw new Error(`Failed to create account for customer ${customer.id}`);
    }

    // Deposit initial funds if requested
    if (initialBalance > 0) {
        const depositPayload = JSON.stringify({
            sourceAccountNumber: account.accountNumber,
            amount: initialBalance,
            type: 'DEPOSIT',
            description: 'Initial benchmark deposit'
        });

        const depRes = http.post(`${baseUrl}/transactions`, depositPayload, {
            headers: {
                ...auth.authHeader,
                'Idempotency-Key': `init-dep-${account.accountNumber}-${timestamp}`
            }
        });

        check(depRes, {
            'initial deposit successful': (r) => r.status === 201,
        });
    }

    return {
        customerId: customer.id,
        email: email,
        password: password,
        token: auth.token,
        authHeader: auth.authHeader,
        accountNumber: account.accountNumber
    };
}
