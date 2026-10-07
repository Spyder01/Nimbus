// Package db connects the worker to Postgres using the settings from the config package.
package db

import (
	"context"
	"fmt"
	"net"
	"net/url"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"nimbus/worker/internal/config"
)

// Connect opens a connection pool using config.Get().DB and checks the database answers.
// config.Load must have succeeded first.
func Connect(ctx context.Context) (*pgxpool.Pool, error) {
	c := config.Get().DB

	pool, err := pgxpool.New(ctx, dsn(c))
	if err != nil {
		return nil, fmt.Errorf("creating postgres pool: %w", err)
	}

	pingCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("connecting to postgres at %s:%d/%s: %w", c.Host, c.Port, c.Name, err)
	}
	return pool, nil
}

// dsn builds the connection URL. The user and password are escaped, so any characters are safe in them.
// It contains the password: never log it.
func dsn(c config.DBConfig) string {
	u := url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(c.User, c.Password),
		Host:   net.JoinHostPort(c.Host, strconv.Itoa(c.Port)),
		Path:   "/" + c.Name,
	}
	return u.String()
}
